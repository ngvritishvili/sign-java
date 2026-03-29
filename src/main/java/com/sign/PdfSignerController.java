package com.sign;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

@RestController
public class PdfSignerController {

    private static final Logger log = LoggerFactory.getLogger(PdfSignerController.class);
    private static final String SIGN_ANCHOR = "[[SIGN_HERE_COMPANY]]";

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(@RequestBody PrepareRequest req) {
        try {
            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());

            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                TextLocationFinder finder = new TextLocationFinder(SIGN_ANCHOR);
                finder.setSortByPosition(true);
                finder.getText(document);

                float stampW = req.getStampWidth() != null ? req.getStampWidth() : 200f;
                float stampH = req.getStampHeight() != null ? req.getStampHeight() : 115f;

                int signPage = finder.isFound() ? finder.getPageIndex() : document.getNumberOfPages() - 1;

                // --- SHIFT ENTIRE STAMP 90px TO THE RIGHT ---
                float signX = finder.isFound() ? (finder.getX() + 110f) : (30f + 110f);

                PDPage page = document.getPage(signPage);
                float pageHeight = page.getMediaBox().getHeight();

                // Calculate Y (Flipped for PDF coordinate system)
                float pdfY = finder.isFound()
                        ? (pageHeight - finder.getY() - stampH)
                        : 30f;

                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
                signature.setName(req.getSignerName() != null ? req.getSignerName() : "Signer");
                signature.setLocation("Georgia");
                signature.setReason("Approve Document");
                signature.setSignDate(Calendar.getInstance());

                PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
                if (acroForm == null) {
                    acroForm = new PDAcroForm(document);
                    document.getDocumentCatalog().setAcroForm(acroForm);
                }
                List<PDField> fields = new ArrayList<>(acroForm.getFields());

                PDSignatureField sigField = new PDSignatureField(acroForm);
                sigField.setPartialName("Sig" + System.currentTimeMillis());

                PDAnnotationWidget widget = sigField.getWidgets().get(0);
                widget.setRectangle(new PDRectangle(signX, pdfY, stampW, stampH));
                widget.setPage(page);
                widget.setPrinted(true);

                PDAppearanceStream apStream = buildAppearance(
                        document, stampW, stampH,
                        req.getSignerFirstName(), req.getSignerLastName(), req.getSignerId(),
                        req.getDate(), req.getTime());

                PDAppearanceDictionary appearance = new PDAppearanceDictionary();
                appearance.setNormalAppearance(apStream);
                widget.setAppearance(appearance);

                page.getAnnotations().add(widget);
                fields.add(sigField);
                acroForm.setFields(fields);

                SignatureOptions opts = new SignatureOptions();
                opts.setPreferredSignatureSize(32768);
                document.addSignature(signature, opts);
                sigField.getCOSObject().setItem(COSName.V, signature.getCOSObject());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ExternalSigningSupport ext = document.saveIncrementalForExternalSigning(baos);

                byte[] hash;
                try (InputStream is = ext.getContent()) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    hash = md.digest(IOUtils.toByteArray(is));
                }
                ext.setSignature(new byte[0]);

                Map<String, Object> response = new HashMap<>();
                response.put("hash", bytesToHex(hash));
                response.put("preparedPdfBase64", Base64.getEncoder().encodeToString(baos.toByteArray()));
                response.put("signPage", signPage + 1);
                response.put("signX", (double) signX);
                response.put("signY", (double) pdfY);
                response.put("signWidth", (double) stampW);
                response.put("signHeight", (double) stampH);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Prepare failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private PDAppearanceStream buildAppearance(
            PDDocument doc, float w, float h,
            String firstName, String lastName, String signerId,
            String date, String time) throws IOException {

        PDAppearanceStream ap = new PDAppearanceStream(doc);
        ap.setResources(new PDResources());
        ap.setBBox(new PDRectangle(w, h));

        PDFont fontRegular;
        PDFont fontBold;
        try (InputStream fontStream = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (fontStream != null) {
                fontRegular = PDType0Font.load(doc, fontStream);
                fontBold = fontRegular;
            } else {
                fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            }
        }

        PDImageXObject logo = null;
        try (InputStream logoStream = getClass().getResourceAsStream("/static/readyLogo.png")) {
            if (logoStream != null) {
                logo = PDImageXObject.createFromByteArray(doc, IOUtils.toByteArray(logoStream), "logo");
            }
        }

        try (PDPageContentStream cs = new PDPageContentStream(doc, ap)) {
            // --- TRANSPARENCY: White background rectangle removed ---

            // Draw Logo (Centered)
            if (logo != null) {
                float imgWidth = logo.getWidth();
                float imgHeight = logo.getHeight();
                float aspectRatio = imgWidth / imgHeight;
                float logoDrawH = h * 0.9f;
                float logoDrawW = logoDrawH * aspectRatio;
                if (logoDrawW > w) {
                    logoDrawW = w * 0.9f;
                    logoDrawH = logoDrawW / aspectRatio;
                }
                cs.drawImage(logo, (w - logoDrawW) / 2f, (h - logoDrawH) / 2f, logoDrawW, logoDrawH);
            }

            cs.setNonStrokingColor(0f, 0f, 0f);

            // Left Side Sizes (12px names, 9px ID)
            float leftZoneW = w * 0.45f;
            float leftX = 8f;
            float nameSize = fitFontSize(fontRegular, firstName, leftZoneW, 12f);
            float idSize = fitFontSize(fontBold, signerId, leftZoneW, 9f);
            float nameLineH = nameSize + 4f;

            float leftStartY = (h + (nameLineH * 2 + idSize)) / 2f - nameSize;

            drawText(cs, fontRegular, nameSize, leftX, leftStartY, firstName);
            drawText(cs, fontRegular, nameSize, leftX, leftStartY - nameLineH, lastName);
            drawText(cs, fontBold, idSize, leftX, leftStartY - nameLineH * 2, signerId);

            // Right Side Sizes (Symmetric increase)
            float rightX = w * 0.55f;
            float rightSize = Math.max(nameSize / 2f + 1.0f, 7.5f);
            float rLH = rightSize + 3.0f;
            float rightStartY = (h + (rLH * 5)) / 2f - rightSize;

            drawText(cs, fontBold, rightSize, rightX, rightStartY, "Digitally signed");
            drawText(cs, fontBold, rightSize, rightX, rightStartY - rLH, "by " + firstName);
            drawText(cs, fontBold, rightSize, rightX, rightStartY - rLH * 2, lastName);
            drawText(cs, fontBold, rightSize, rightX, rightStartY - rLH * 3, "Date: " + date);
            drawText(cs, fontBold, rightSize, rightX, rightStartY - rLH * 4, time);
        }
        return ap;
    }

    private void drawText(PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
    }

    private float fitFontSize(PDFont font, String text, float maxWidth, float preferred) throws IOException {
        if (text == null) return preferred;
        float size = preferred;
        while (size > 5f) {
            float tw = font.getStringWidth(text) / 1000f * size;
            if (tw <= maxWidth) break;
            size -= 0.5f;
        }
        return size;
    }

    @PostMapping("/finalize")
    public ResponseEntity<?> finalize(@RequestBody FinalizeRequest req) {
        try {
            byte[] preparedPdfBytes = Base64.getDecoder().decode(req.getPrepared_pdf_b64());
            byte[] cmsBytes = req.getCms_hex().matches("^[0-9a-fA-F]+$")
                    ? hexStringToByteArray(req.getCms_hex()) : Base64.getDecoder().decode(req.getCms_hex());

            try (PDDocument pdDocument = Loader.loadPDF(preparedPdfBytes)) {
                PDSignature signature = pdDocument.getLastSignatureDictionary();
                int[] byteRange = signature.getByteRange();
                long offset = byteRange[1] + 1;
                int reserved = byteRange[2] - byteRange[1] - 2;

                if (cmsBytes.length > reserved) throw new Exception("CMS size exceeds reserved space");

                byte[] signedPdf = preparedPdfBytes.clone();
                byte[] cmsHexBytes = bytesToHex(cmsBytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                System.arraycopy(cmsHexBytes, 0, signedPdf, (int) offset, cmsHexBytes.length);

                return ResponseEntity.ok(Map.of("signedPdfBase64", Base64.getEncoder().encodeToString(signedPdf)));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2)
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        return out;
    }

    private static class TextLocationFinder extends PDFTextStripper {
        private final String target;
        private float x, y;
        private int pageIndex = 0;
        private boolean found = false;

        public TextLocationFinder(String target) throws IOException {
            this.target = target;
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (!found && string.contains(target)) {
                TextPosition first = textPositions.get(0);
                this.x = first.getXDirAdj();
                this.y = first.getYDirAdj();
                this.pageIndex = getCurrentPageNo() - 1;
                this.found = true;
            }
        }

        public boolean isFound() {
            return found;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public int getPageIndex() {
            return pageIndex;
        }
    }

    public static class PrepareRequest {
        @JsonProperty("source_pdf_b64")
        private String source_pdf_b64;
        @JsonProperty("signer_name")
        private String signerName;
        @JsonProperty("signer_id")
        private String signerId;
        @JsonProperty("signer_first_name")
        private String signerFirstName;
        @JsonProperty("signer_last_name")
        private String signerLastName;
        @JsonProperty("date")
        private String date;
        @JsonProperty("time")
        private String time;
        @JsonProperty("stamp_width")
        private Float stampWidth;
        @JsonProperty("stamp_height")
        private Float stampHeight;

        // Getters/Setters...
        public String getSource_pdf_b64() {
            return source_pdf_b64;
        }

        public String getSignerName() {
            return signerName;
        }

        public String getSignerId() {
            return signerId;
        }

        public String getSignerFirstName() {
            return signerFirstName;
        }

        public String getSignerLastName() {
            return signerLastName;
        }

        public String getDate() {
            return date;
        }

        public String getTime() {
            return time;
        }

        public Float getStampWidth() {
            return stampWidth;
        }

        public Float getStampHeight() {
            return stampHeight;
        }
    }

    public static class FinalizeRequest {
        @JsonProperty("prepared_pdf_b64")
        private String prepared_pdf_b64;
        @JsonProperty("cms_hex")
        private String cms_hex;

        public String getPrepared_pdf_b64() {
            return prepared_pdf_b64;
        }

        public String getCms_hex() {
            return cms_hex;
        }
    }
}