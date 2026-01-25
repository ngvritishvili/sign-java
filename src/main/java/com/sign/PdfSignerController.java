package com.sign;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
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

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(@RequestBody PrepareRequest req) {
        try {
            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());

            try (PDDocument document = Loader.loadPDF(pdfBytes)) {

                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);

                signature.setName("Invoice Signer");
                signature.setLocation("Georgia");
                signature.setReason("Approve Invoice");
                signature.setSignDate(Calendar.getInstance());

                SignatureOptions opts = new SignatureOptions();
                opts.setPreferredSignatureSize(32768); // 32 KB
                document.addSignature(signature, opts);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ExternalSigningSupport ext = document.saveIncrementalForExternalSigning(baos);

                // Hash the content
                byte[] dataToHash;
                try (InputStream is = ext.getContent()) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    dataToHash = md.digest(IOUtils.toByteArray(is));
                }

                // Leave placeholder empty — DO NOT MODIFY THE DOCUMENT
                ext.setSignature(new byte[0]);

                Map<String, String> response = new HashMap<>();
                response.put("hash", bytesToHex(dataToHash));
                response.put("preparedPdfBase64", Base64.getEncoder().encodeToString(baos.toByteArray()));

                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            log.error("prepare failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.toString()));
        }
    }

    @PostMapping("/finalize")
    public ResponseEntity<?> finalize(@RequestBody FinalizeRequest req) {
        try {
            // 1. Decode the prepared PDF and CMS
            byte[] preparedPdfBytes = Base64.getDecoder().decode(req.getPrepared_pdf_b64());
            byte[] cmsBytes = (req.getCms_hex().matches("^[0-9a-fA-F]+$"))
                    ? hexStringToByteArray(req.getCms_hex())
                    : Base64.getDecoder().decode(req.getCms_hex());

            // 2. Load the document to find the ByteRange
            try (PDDocument pdDocument = Loader.loadPDF(preparedPdfBytes)) {
                PDSignature signature = pdDocument.getLastSignatureDictionary();
                if (signature == null) {
                    throw new IllegalStateException("No signature dictionary found");
                }

                // The ByteRange looks like: [0, 100, 150, 500]
                // The signature is located between index 1 and index 2 of the range
                int[] byteRange = signature.getByteRange();
                long offset = byteRange[1] + 1; // Start of the gap
                int reservedLength = byteRange[2] - byteRange[1] - 2; // Size of the gap

                if (cmsBytes.length > reservedLength) {
                    throw new Exception("CMS signature (" + cmsBytes.length + " bytes) is larger than reserved space (" + reservedLength + " bytes)");
                }

                // 3. Surgical Injection: We write directly into the byte array
                // We do NOT use pdDocument.save() because we want to preserve the exact binary structure
                byte[] signedPdfBytes = preparedPdfBytes.clone();

                // Hex-encode the CMS bytes as required by the PDF spec for the /Contents entry
                // This turns binary CMS into Hex strings that fit in the < > brackets
                String cmsHex = bytesToHex(cmsBytes);
                byte[] cmsHexBytes = cmsHex.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

                // Copy the hex bytes into the offset position of our PDF copy
                System.arraycopy(cmsHexBytes, 0, signedPdfBytes, (int)offset, cmsHexBytes.length);

                Map<String, String> response = new HashMap<>();
                response.put("signedPdfBase64", Base64.getEncoder().encodeToString(signedPdfBytes));
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Finalize error", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }



    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        return data;
    }

    public static class PrepareRequest {
        @JsonProperty("source_pdf_b64")
        private String source_pdf_b64;
        public String getSource_pdf_b64() { return source_pdf_b64; }
        public void setSource_pdf_b64(String s) { this.source_pdf_b64 = s; }
    }

    public static class FinalizeRequest {
        @JsonProperty("prepared_pdf_b64")
        private String prepared_pdf_b64;
        @JsonProperty("cms_hex")
        private String cms_hex;

        public String getPrepared_pdf_b64() { return prepared_pdf_b64; }
        public void setPrepared_pdf_b64(String v) { this.prepared_pdf_b64 = v; }

        public String getCms_hex() { return cms_hex; }
        public void setCms_hex(String v) { this.cms_hex = v; }
    }
}
