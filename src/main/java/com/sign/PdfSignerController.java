package com.sign;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

@RestController
public class PdfSignerController {

    private static final Logger log = LoggerFactory.getLogger(PdfSignerController.class);

    // ---------------------------
    // PREPARE
    // ---------------------------
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


    // ---------------------------
    // FINALIZE
    // ---------------------------
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



    // ---------------------------
    // UTILITIES
    // ---------------------------
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

    // ---------------------------
    // DTOs
    // ---------------------------
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


//@PostMapping("/finalize")
//public ResponseEntity<?> finalize(@RequestBody FinalizeRequest req) {
//    System.out.println("=== ENTERED /finalize ===");
//    System.out.println("Request received: " + req);
//
//    try {
//        // 1. Validate input
//        if (req.getPrepared_pdf_b64() == null || req.getPrepared_pdf_b64().trim().isEmpty()) {
//            throw new IllegalArgumentException("prepared_pdf_b64 is required and cannot be empty");
//        }
//
//        String cmsInput = req.getCms_hex();
//        if (cmsInput == null || cmsInput.trim().isEmpty()) {
//            throw new IllegalArgumentException("cms_hex is required and cannot be empty");
//        }
//
//        System.out.println("CMS input length: " + cmsInput.length() + " chars");
//        System.out.println("CMS starts with: " + cmsInput.substring(0, Math.min(60, cmsInput.length())) + "...");
//
//        // 2. Decode prepared PDF
//        byte[] preparedPdfBytes;
//        try {
//            preparedPdfBytes = Base64.getDecoder().decode(req.getPrepared_pdf_b64().trim());
//            System.out.println("Prepared PDF decoded successfully. Size: " + preparedPdfBytes.length + " bytes");
//        } catch (IllegalArgumentException e) {
//            throw new IllegalArgumentException("Invalid base64 in prepared_pdf_b64: " + e.getMessage());
//        }
//
//        // 3. Convert CMS hex → bytes
//        byte[] cmsBytes;
//        try {
//            if (cmsInput.length() > 100 && cmsInput.matches("^[0-9a-fA-F]+$")) {
//                System.out.println("Detected HEX format for CMS");
//                cmsBytes = hexStringToByteArray(cmsInput);
//            } else {
//                System.out.println("Treating CMS as base64");
//                cmsBytes = Base64.getDecoder().decode(cmsInput.trim());
//            }
//            System.out.println("CMS bytes size: " + cmsBytes.length + " bytes");
//        } catch (Exception e) {
//            throw new IllegalArgumentException("Failed to decode CMS (hex/base64): " + e.getMessage());
//        }
//
//        // 4. DEBUG: Save received prepared PDF
//        try (java.io.FileOutputStream fos = new java.io.FileOutputStream("debug_received_prepared.pdf")) {
//            fos.write(preparedPdfBytes);
//            System.out.println("Saved received prepared PDF → debug_received_prepared.pdf");
//        } catch (Exception e) {
//            System.err.println("Failed to save debug_received_prepared.pdf: " + e.getMessage());
//        }
//
//        // 5. Load PDF and inspect signatures
//        try (PDDocument pdDocument = PDDocument.load(preparedPdfBytes)) {
//            System.out.println("PDF loaded successfully. Number of pages: " + pdDocument.getNumberOfPages());
//
//            List<PDSignature> signatures = pdDocument.getSignatureDictionaries();
//            System.out.println("Found " + signatures.size() + " signature placeholder(s)");
//
//            if (signatures.isEmpty()) {
//                throw new IllegalStateException("No signature placeholder found in prepared PDF – signing will be invisible/invalid");
//            }
//
//            // Take the last one (most recent placeholder)
//            PDSignature signature = signatures.get(signatures.size() - 1);
//            System.out.println("Using signature placeholder #" + (signatures.size()));
//            System.out.println("  - Name:   " + signature.getName());
//            System.out.println("  - Reason: " + signature.getReason());
//            System.out.println("  - Location: " + signature.getLocation());
//            System.out.println("  - Filter/SubFilter: " + signature.getFilter() + " / " + signature.getSubFilter());
//            System.out.println("  - ByteRange exists: " + (signature.getByteRange() != null));
//            if (signature.getByteRange() != null) {
//                System.out.println("  - ByteRange: " + java.util.Arrays.toString(signature.getByteRange()));
//            }
//
//            // 6. Embed CMS
//            signature.setContents(cmsBytes);
//            System.out.println("CMS contents set successfully (size: " + cmsBytes.length + " bytes)");
//
//            // 7. Incremental save
//            ByteArrayOutputStream signedBaos = new ByteArrayOutputStream();
//            pdDocument.saveIncremental(signedBaos);
//            byte[] signedPdfBytes = signedBaos.toByteArray();
//
//            System.out.println("Incremental save completed. Final signed PDF size: " + signedPdfBytes.length + " bytes");
//
//            // 8. DEBUG: Save final PDF
//            try (java.io.FileOutputStream fos = new java.io.FileOutputStream("debug_final_signed.pdf")) {
//                fos.write(signedPdfBytes);
//                System.out.println("Saved final signed PDF → debug_final_signed.pdf");
//            } catch (Exception e) {
//                System.err.println("Failed to save debug_final_signed.pdf: " + e.getMessage());
//            }
//
//            // 9. Return response
//            Map<String, String> response = new HashMap<>();
//            response.put("signedPdfBase64", Base64.getEncoder().encodeToString(signedPdfBytes));
//
//            System.out.println("=== FINALIZE SUCCESS ===");
//            return ResponseEntity.ok(response);
//
//        } // auto-closes pdDocument
//
//    } catch (Exception e) {
//        System.err.println("Finalize FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
//        e.printStackTrace();
//        log.error("Finalize error", e);
//        return ResponseEntity.status(500).body(Map.of(
//                "error", e.getMessage(),
//                "exception", e.getClass().getSimpleName()
//        ));
//    }
//}