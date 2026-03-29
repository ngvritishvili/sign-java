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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
public class PdfSignerController {

    private static final Logger log = LoggerFactory.getLogger(PdfSignerController.class);
    private static final String SIGN_ANCHOR = "[[SIGN_HERE]]";

    // -------------------------------------------------------------------------
    // /prepare
    // -------------------------------------------------------------------------
    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(@RequestBody PrepareRequest req) {
        try {
            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());

            try (PDDocument document = Loader.loadPDF(pdfBytes)) {

                // --- 1. Find [[SIGN_HERE]] anchor ---
                TextLocationFinder finder = new TextLocationFinder(SIGN_ANCHOR);
                finder.setSortByPosition(true);
                finder.getText(document);

                float stampW = req.getStampWidth()  != null ? req.getStampWidth()  : 340f;
                float stampH = req.getStampHeight() != null ? req.getStampHeight() : 100f;

                int   signPage = finder.isFound() ? finder.getPageIndex() : document.getNumberOfPages() - 1;
                float signX    = finder.isFound() ? finder.getX() : 30f;

                PDPage page      = document.getPage(signPage);
                float pageHeight = page.getMediaBox().getHeight();
                float pdfY       = finder.isFound()
                        ? (pageHeight - finder.getY() - stampH)
                        : 30f;

                // --- 2. Signature dictionary ---
                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
                signature.setName(req.getSignerName() != null ? req.getSignerName() : "Signer");
                signature.setLocation("Georgia");
                signature.setReason("Approve Invoice");
                signature.setSignDate(Calendar.getInstance());

                // --- 3. AcroForm ---
                PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
                if (acroForm == null) {
                    acroForm = new PDAcroForm(document);
                    document.getDocumentCatalog().setAcroForm(acroForm);
                }
                List<PDField> fields = new ArrayList<>(acroForm.getFields());

                // --- 4. Signature field ---
                PDSignatureField sigField = new PDSignatureField(acroForm);
                sigField.setPartialName("Sig1");

                // --- 5. Widget at anchor position ---
                PDAnnotationWidget widget = sigField.getWidgets().get(0);
                widget.setRectangle(new PDRectangle(signX, pdfY, stampW, stampH));
                widget.setPage(page);
                widget.setPrinted(true);

                // --- 6. Build visual appearance ---
                String signerFirstName = req.getSignerFirstName() != null ? req.getSignerFirstName() : "";
                String signerLastName  = req.getSignerLastName()  != null ? req.getSignerLastName()  : "";
                String signerId        = req.getSignerId()        != null ? req.getSignerId()        : "";
                String date            = req.getDate()            != null ? req.getDate()            : java.time.LocalDate.now().toString();
                String time            = req.getTime()            != null ? req.getTime()            : java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

                // Create the appearance stream
                PDAppearanceStream apStream = buildAppearance(
                        document, stampW, stampH,
                        signerFirstName, signerLastName, signerId,
                        date, time);

                // FIX: Initialize the appearance dictionary
                PDAppearanceDictionary appearance = new PDAppearanceDictionary();
                appearance.setNormalAppearance(apStream);
                widget.setAppearance(appearance);

                page.getAnnotations().add(widget);

                // --- 7. Add field ---
                fields.add(sigField);
                acroForm.setFields(fields);

                // --- 8. Register signature ---
                SignatureOptions opts = new SignatureOptions();
                opts.setPreferredSignatureSize(32768);
                document.addSignature(signature, opts);
                sigField.getCOSObject().setItem(COSName.V, signature.getCOSObject());

                // --- 9. External signing ---
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ExternalSigningSupport ext  = document.saveIncrementalForExternalSigning(baos);

                byte[] hash;
                try (InputStream is = ext.getContent()) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    hash = md.digest(IOUtils.toByteArray(is));
                }
                ext.setSignature(new byte[0]);

                // --- 10. Response ---
                Map<String, Object> response = new HashMap<>();
                response.put("hash",              bytesToHex(hash));
                response.put("preparedPdfBase64", Base64.getEncoder().encodeToString(baos.toByteArray()));
                response.put("signPage",          signPage + 1);
                response.put("signX",             (double) signX);
                response.put("signY",             (double) pdfY);
                response.put("signWidth",         (double) stampW);
                response.put("signHeight",        (double) stampH);
                response.put("anchorFound",       finder.isFound());

                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            log.error("Prepare failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private PDAppearanceStream buildAppearance(
            PDDocument doc,
            float w, float h,
            String firstName,
            String lastName,
            String signerId,
            String date,
            String time) throws IOException {

        PDAppearanceStream ap = new PDAppearanceStream(doc);
        ap.setResources(new PDResources());
        ap.setBBox(new PDRectangle(w, h));

        // --- Load Unicode Font for Georgian Support ---
        // Place a .ttf file in src/main/resources/fonts/
        PDFont fontRegular;
        PDFont fontBold;
        try (InputStream fontStream = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (fontStream != null) {
                fontRegular = PDType0Font.load(doc, fontStream);
                fontBold = fontRegular; // Use same for simplicity, or load a Bold .ttf
            } else {
                // Fallback to standard if font file missing (will still error on Georgian)
                fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            }
        }

        // Load logo
        PDImageXObject logo = null;
        try (InputStream logoStream = getClass().getResourceAsStream("/static/readyLogo.png")) {
            if (logoStream != null) {
                logo = PDImageXObject.createFromByteArray(doc, IOUtils.toByteArray(logoStream), "logo");
            }
        }

//        PDType1Font fontBold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
//        PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        try (PDPageContentStream cs = new PDPageContentStream(doc, ap)) {

            // --- White background ---
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(0, 0, w, h);
            cs.fill();

            // --- Logo: Fixed Aspect Ratio (No Stretching) ---
            if (logo != null) {
                float imgWidth = logo.getWidth();
                float imgHeight = logo.getHeight();
                float aspectRatio = imgWidth / imgHeight;

                float logoDrawH = h;
                float logoDrawW = h * aspectRatio;

                if (logoDrawW > w) {
                    logoDrawW = w;
                    logoDrawH = w / aspectRatio;
                }

                float logoX = (w - logoDrawW) / 2f;
                float logoY = (h - logoDrawH) / 2f;
                cs.drawImage(logo, logoX, logoY, logoDrawW, logoDrawH);
            }

            // ---------------------------------------------------------------
            // LEFT SIDE — Moved 5% more to the right
            // ---------------------------------------------------------------
            float leftZoneW  = w * 0.40f;
            float leftX      = (w * 0.05f) + 8f;

            float leftNameSize = fitFontSize(fontRegular, firstName, leftZoneW, 14f);
            float lastNameSize = fitFontSize(fontRegular, lastName,  leftZoneW, 14f);
            float idSize       = fitFontSize(fontBold,    signerId,  leftZoneW, 11f);

            float nameSize = Math.min(leftNameSize, lastNameSize);
            float nameLineH = nameSize + 4f;
            float idLineH   = idSize   + 4f;

            float leftBlockH = nameLineH + nameLineH + idLineH;
            float leftStartY = (h + leftBlockH) / 2f - nameSize;

            cs.setNonStrokingColor(0f, 0f, 0f);

            // First name (Regular)
            cs.beginText();
            cs.setFont(fontRegular, nameSize);
            cs.newLineAtOffset(leftX, leftStartY);
            cs.showText(firstName);
            cs.endText();

            // Last name (Regular)
            cs.beginText();
            cs.setFont(fontRegular, nameSize);
            cs.newLineAtOffset(leftX, leftStartY - nameLineH);
            cs.showText(lastName);
            cs.endText();

            // ID (Bold)
            cs.beginText();
            cs.setFont(fontBold, idSize);
            cs.newLineAtOffset(leftX, leftStartY - nameLineH - nameLineH);
            cs.showText(signerId);
            cs.endText();

            // ---------------------------------------------------------------
            // RIGHT SIDE — Increased size by ~1.5px and set to Bold
            // ---------------------------------------------------------------
            float rightX     = w * 0.60f;
            // Increased rightSize from nameSize/2 to nameSize/2 + 1.5f
            float rightSize  = (nameSize / 2f) + 1.5f;
            float rightLineH2 = rightSize + 3.5f; // Slight adjust to line height for larger font

            float rightBlockH = 5f * rightLineH2;
            float rightStartY = (h + rightBlockH) / 2f - rightSize;

            // Line 1: "Digitally signed"
            cs.beginText();
            cs.setFont(fontBold, rightSize);
            cs.newLineAtOffset(rightX, rightStartY);
            cs.showText("Digitally signed");
            cs.endText();

            // Line 2: "by FirstName"
            cs.beginText();
            cs.setFont(fontBold, rightSize);
            cs.newLineAtOffset(rightX, rightStartY - rightLineH2);
            cs.showText("by " + firstName);
            cs.endText();

            // Line 3: LastName
            cs.beginText();
            cs.setFont(fontBold, rightSize);
            cs.newLineAtOffset(rightX, rightStartY - rightLineH2 * 2f);
            cs.showText(lastName);
            cs.endText();

            // Line 4: Date
            cs.beginText();
            cs.setFont(fontBold, rightSize);
            cs.newLineAtOffset(rightX, rightStartY - rightLineH2 * 3f);
            cs.showText("Date: " + date);
            cs.endText();

            // Line 5: Time
            cs.beginText();
            cs.setFont(fontBold, rightSize);
            cs.newLineAtOffset(rightX, rightStartY - rightLineH2 * 4f);
            cs.showText(time);
            cs.endText();
        }

        return ap;
    }

    // -------------------------------------------------------------------------
    // Fit font size so text width <= maxWidth, starting from preferred size
    // -------------------------------------------------------------------------
    private float fitFontSize(PDFont font, String text, float maxWidth, float preferred)
            throws IOException {
        float size = preferred;
        while (size > 5f) {
            // This method exists in the base PDFont class, so it works for all fonts
            float tw = font.getStringWidth(text) / 1000f * size;
            if (tw <= maxWidth) break;
            size -= 0.5f;
        }
        return size;
    }

    // -------------------------------------------------------------------------
    // Split name at last space before or at midpoint
    // -------------------------------------------------------------------------
    private String[] splitName(String name) {
        if (name == null || !name.contains(" ")) return new String[]{name};
        int mid = name.length() / 2;
        // find nearest space to midpoint
        int before = name.lastIndexOf(' ', mid);
        int after  = name.indexOf(' ', mid);
        int split;
        if (before < 0) split = after;
        else if (after < 0) split = before;
        else split = (mid - before <= after - mid) ? before : after;
        return new String[]{
                name.substring(0, split).trim(),
                name.substring(split).trim()
        };
    }

    // -------------------------------------------------------------------------
    // /finalize
    // -------------------------------------------------------------------------
    @PostMapping("/finalize")
    public ResponseEntity<?> finalize(@RequestBody FinalizeRequest req) {
        try {
            byte[] preparedPdfBytes = Base64.getDecoder().decode(req.getPrepared_pdf_b64());
            byte[] cmsBytes = req.getCms_hex().matches("^[0-9a-fA-F]+$")
                    ? hexStringToByteArray(req.getCms_hex())
                    : Base64.getDecoder().decode(req.getCms_hex());

            try (PDDocument pdDocument = Loader.loadPDF(preparedPdfBytes)) {
                PDSignature signature = pdDocument.getLastSignatureDictionary();
                if (signature == null) throw new IllegalStateException("Signature dictionary missing");

                int[] byteRange    = signature.getByteRange();
                long  offset       = byteRange[1] + 1;
                int   reserved     = byteRange[2] - byteRange[1] - 2;

                if (cmsBytes.length > reserved)
                    throw new Exception("CMS size (" + cmsBytes.length + ") exceeds reserved (" + reserved + ")");

                byte[] signedPdf   = preparedPdfBytes.clone();
                byte[] cmsHexBytes = bytesToHex(cmsBytes)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                System.arraycopy(cmsHexBytes, 0, signedPdf, (int) offset, cmsHexBytes.length);

                return ResponseEntity.ok(Map.of(
                        "signedPdfBase64", Base64.getEncoder().encodeToString(signedPdf)));
            }

        } catch (Exception e) {
            log.error("Finalize failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2)
            out[i / 2] = (byte)((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        return out;
    }

    // -------------------------------------------------------------------------
    // TextLocationFinder
    // -------------------------------------------------------------------------
    private static class TextLocationFinder extends PDFTextStripper {
        private final String target;
        private float x, y;
        private int   pageIndex = 0;
        private boolean found   = false;

        public TextLocationFinder(String target) throws IOException { this.target = target; }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (!found && string.contains(target)) {
                TextPosition first = textPositions.get(0);
                this.x         = first.getXDirAdj();
                this.y         = first.getYDirAdj();
                this.pageIndex = getCurrentPageNo() - 1;
                this.found     = true;
            }
        }

        public boolean isFound()      { return found; }
        public float   getX()         { return x; }
        public float   getY()         { return y; }
        public int     getPageIndex() { return pageIndex; }
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
// Updated PrepareRequest DTO — add new fields
// -------------------------------------------------------------------------
    public static class PrepareRequest {
        @JsonProperty("source_pdf_b64")    private String source_pdf_b64;
        @JsonProperty("signer_name")       private String signerName;
        @JsonProperty("signer_id")         private String signerId;
        @JsonProperty("signer_first_name") private String signerFirstName;
        @JsonProperty("signer_last_name")  private String signerLastName;
        @JsonProperty("date")              private String date;
        @JsonProperty("time")              private String time;
        @JsonProperty("stamp_width")       private Float  stampWidth;
        @JsonProperty("stamp_height")      private Float  stampHeight;

        public String getSource_pdf_b64()           { return source_pdf_b64; }
        public void   setSource_pdf_b64(String s)   { this.source_pdf_b64 = s; }
        public String getSignerName()               { return signerName; }
        public void   setSignerName(String v)       { this.signerName = v; }
        public String getSignerId()                 { return signerId; }
        public void   setSignerId(String v)         { this.signerId = v; }
        public String getSignerFirstName()          { return signerFirstName; }
        public void   setSignerFirstName(String v)  { this.signerFirstName = v; }
        public String getSignerLastName()           { return signerLastName; }
        public void   setSignerLastName(String v)   { this.signerLastName = v; }
        public String getDate()                     { return date; }
        public void   setDate(String v)             { this.date = v; }
        public String getTime()                     { return time; }
        public void   setTime(String v)             { this.time = v; }
        public Float  getStampWidth()               { return stampWidth; }
        public void   setStampWidth(Float v)        { this.stampWidth = v; }
        public Float  getStampHeight()              { return stampHeight; }
        public void   setStampHeight(Float v)       { this.stampHeight = v; }
    }

    public static class FinalizeRequest {
        @JsonProperty("prepared_pdf_b64") private String prepared_pdf_b64;
        @JsonProperty("cms_hex")          private String cms_hex;

        public String getPrepared_pdf_b64()        { return prepared_pdf_b64; }
        public void   setPrepared_pdf_b64(String v){ this.prepared_pdf_b64 = v; }
        public String getCms_hex()                 { return cms_hex; }
        public void   setCms_hex(String v)         { this.cms_hex = v; }
    }
}