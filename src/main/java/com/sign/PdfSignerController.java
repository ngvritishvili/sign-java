package com.sign;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for DSS Services
 */
@Configuration
class DssConfig {
    @Bean
    public PAdESService padesService() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        return new PAdESService(verifier);
    }
}

/**
 * Controller providing the Prepare and Embed endpoints for PDF Signing with external CMS
 */
@RestController
public class PdfSignerController {

    private final PAdESService padesService;

    private static final Logger log = LoggerFactory.getLogger(PdfSignerController.class);

    public PdfSignerController(PAdESService padesService) {
        this.padesService = padesService;
    }

    /**
     * Step 1: Prepare the PDF with signature placeholder and compute the hash/digest
     */
    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(@RequestBody PrepareRequest req) {
        System.out.println("Enters");
        try {
            if (req.getSource_pdf_b64() == null) {
                throw new IllegalArgumentException("source_pdf_b64 is required");
            }

            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());

            // Load PDF
            PDDocument pdDocument = PDDocument.load(pdfBytes);
            System.out.println("File");
            System.out.println(pdDocument);
            // Create signature dictionary
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);

            // Optional: Set signature properties
            signature.setName("Invoice Signer");
            signature.setLocation("Georgia");
            signature.setReason("Approve Invoice");

            // Reserve space for CMS (large enough for B level)
            int reservedSize = 32768; // 32KB - safe for CMS with cert
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(reservedSize);
            pdDocument.addSignature(signature, options);

            // Get the external signing support to compute the hash
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ExternalSigningSupport externalSigning = pdDocument.saveIncrementalForExternalSigning(baos);

            // Compute hash of the prepared byte ranges
            byte[] content = externalSigning.getContent().readAllBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = md.digest(content);

            // Save prepared PDF bytes (after hash computation)
            externalSigning.setSignature(new byte[0]); // dummy to close
            byte[] preparedPdfBytes = baos.toByteArray();

            // Close document
            pdDocument.close();

            // Return digest as hex (for UniTool) and prepared PDF b64 (for embed)
            String hexDigest = bytesToHex(digestBytes).toUpperCase();
            String preparedPdfB64 = Base64.getEncoder().encodeToString(preparedPdfBytes);

            Map<String, String> response = new HashMap<>();
            response.put("hash_to_sign_hex", hexDigest);
            response.put("prepared_pdf_b64", preparedPdfB64);
            System.out.println("Comes to end");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 2: Embed the external CMS into the prepared PDF
     */
    @PostMapping("/embed")
    public ResponseEntity<?> embed(@RequestBody EmbedRequest req) {
        System.out.println("Enters .Embed");
        try {
            if (req.getPrepared_pdf_b64() == null) {
                throw new IllegalArgumentException("prepared_pdf_b64 is required");
            }
            if (req.getCms_b64() == null) {
                throw new IllegalArgumentException("cms_b64 is required");
            }

            byte[] preparedPdfBytes = Base64.getDecoder().decode(req.getPrepared_pdf_b64());
            byte[] cmsBytes = Base64.getDecoder().decode(req.getCms_b64());

            System.out.println("Received CMS size: " + cmsBytes.length + " bytes");

            // DEBUG: Save received prepared PDF
            try (FileOutputStream fos = new FileOutputStream("debug_received_prepared.pdf")) {
                fos.write(preparedPdfBytes);
                System.out.println("Saved received prepared PDF to: debug_received_prepared.pdf");
            }

            // Load the prepared PDF
            PDDocument pdDocument = PDDocument.load(preparedPdfBytes);

            // Find the signature dictionary (last one)
            List<PDSignature> signatures = pdDocument.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                pdDocument.close();
                throw new IllegalStateException("No signature placeholder found in prepared PDF");
            }
            PDSignature signature = signatures.get(signatures.size() - 1);

            // Embed the CMS bytes directly
            signature.setContents(cmsBytes);

            // Save incremental
            ByteArrayOutputStream signedBaos = new ByteArrayOutputStream();
            pdDocument.saveIncremental(signedBaos);
            byte[] signedPdfBytes = signedBaos.toByteArray();

            pdDocument.close();

            // DEBUG: Save final signed PDF
            try (FileOutputStream fos = new FileOutputStream("debug_final_signed_in_java.pdf")) {
                fos.write(signedPdfBytes);
                System.out.println("Saved final signed PDF to: debug_final_signed_in_java.pdf");
            }

            Map<String, String> response = new HashMap<>();
            response.put("signed_pdf_b64", Base64.getEncoder().encodeToString(signedPdfBytes));
            System.out.println("Ends .Embed");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // Helper: byte[] to hex string
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // DTOs
    public static class PrepareRequest {
        private String source_pdf_b64;

        @JsonProperty("source_pdf_b64")
        public String getSource_pdf_b64() { return source_pdf_b64; }

        @JsonProperty("source_pdf_b64")
        public void setSource_pdf_b64(String source_pdf_b64) { this.source_pdf_b64 = source_pdf_b64; }
    }

    public static class EmbedRequest {
        private String prepared_pdf_b64;
        private String cms_b64;

        @JsonProperty("prepared_pdf_b64")
        public String getPrepared_pdf_b64() { return prepared_pdf_b64; }

        @JsonProperty("prepared_pdf_b64")
        public void setPrepared_pdf_b64(String b64) { this.prepared_pdf_b64 = b64; }

        @JsonProperty("cms_b64")
        public String getCms_b64() { return cms_b64; }

        @JsonProperty("cms_b64")
        public void setCms_b64(String b64) { this.cms_b64 = b64; }
    }
}

//package com.sign;
//
//import eu.europa.esig.dss.enumerations.DigestAlgorithm;
//import eu.europa.esig.dss.enumerations.SignatureLevel;
//import eu.europa.esig.dss.model.DSSDocument;
//import eu.europa.esig.dss.model.InMemoryDocument;
//import eu.europa.esig.dss.model.SignatureValue;
//import eu.europa.esig.dss.model.ToBeSigned;
//import eu.europa.esig.dss.pades.PAdESSignatureParameters;
//import eu.europa.esig.dss.pades.signature.PAdESService;
//import eu.europa.esig.dss.validation.CommonCertificateVerifier;
//import org.apache.pdfbox.pdmodel.PDDocument;
//import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
//import org.apache.tomcat.util.http.fileupload.ByteArrayOutputStream;
//import org.bouncycastle.jce.provider.BouncyCastleProvider;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import javax.xml.bind.DatatypeConverter;
//import java.security.Security;
//import java.util.Base64;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
///**
// * Configuration for DSS Services
// */
//@Configuration
//class DssConfig {
//    @Bean
//    public PAdESService padesService() {
//        if (Security.getProvider("BC") == null) {
//            Security.addProvider(new BouncyCastleProvider());
//        }
//        // CommonCertificateVerifier is the engine that validates certificates.
//        // For simple hash-signing, we initialize it with default settings.
//        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
//        return new PAdESService(verifier);
//    }
//}
//
///**
// * Controller providing the Prepare and Finalize endpoints for PDF Signing
// */
//@RestController
////@RequestMapping("/api/pdf")
//public class PdfSignerController {
//
//    private static final Logger logger = LoggerFactory.getLogger(PdfSignerController.class);
//
//    private final PAdESService padesService;
//
//    public PdfSignerController(PAdESService padesService) {
//        this.padesService = padesService;
//    }
//
//    /**
//     * Step 1: Prepare the PDF and return the Hash to be signed by the client/HSM
//     */
//    @PostMapping("/prepare")
//    public ResponseEntity<?> prepare(@RequestBody PrepareRequest req) {
//
//        try {
//            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());
//            DSSDocument toSignDocument = new InMemoryDocument(pdfBytes);
//
//            PAdESSignatureParameters parameters = new PAdESSignatureParameters();
//            parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
//            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
//
//            // This is critical for remote signing
//            parameters.setGenerateTBSWithoutCertificate(true);
//
//            ToBeSigned dataToSign = padesService.getDataToSign(toSignDocument, parameters);
//
//            Map<String, String> response = new HashMap<>();
//            logger.info("This is an info log");
//
//            logger.error(dataToSign.toString());
//            // THIS KEY NAME MUST MATCH LARAVEL
//            response.put("hash_to_sign_b64", Base64.getEncoder().encodeToString(dataToSign.getBytes()));
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
//        }
//    }
//    /**
//     * Step 2: Receive the signed hash and inject it back into the PDF
//     */
//    @PostMapping("/finalize")
//    public ResponseEntity<?> finalize(@RequestBody FinalizeRequest req) {
//        try {
//            // 1. Setup original document and parameters (Must match Step 1)
//            byte[] pdfBytes = Base64.getDecoder().decode(req.getSource_pdf_b64());
//            DSSDocument toSignDocument = new InMemoryDocument(pdfBytes);
//
//            PAdESSignatureParameters parameters = new PAdESSignatureParameters();
//            parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
//            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
//
//            // 2. Wrap the external signature into a DSS SignatureValue
//            byte[] signatureValueBytes = Base64.getDecoder().decode(req.getSignature_b64());
//            SignatureValue signatureValue = new SignatureValue(
//                    eu.europa.esig.dss.enumerations.SignatureAlgorithm.RSA_SHA256,
//                    signatureValueBytes
//            );
//
//            // 3. Create the signed PDF
//            DSSDocument signedDocument = padesService.signDocument(toSignDocument, parameters, signatureValue);
//
//            // 4. Return as Base64
//            byte[] resultBytes = signedDocument.openStream().readAllBytes();
//            return ResponseEntity.ok(Map.of("signed_pdf_b64", Base64.getEncoder().encodeToString(resultBytes)));
//
//        } catch (Exception e) {
//            return ResponseEntity.status(500).body(Map.of("error", "Finalization failed: " + e.getMessage()));
//        }
//    }
//
//    @PostMapping("/embed")
//    public ResponseEntity<?> embedCms(@RequestBody EmbedRequest req) {
//        try {
//            byte[] originalPdfBytes = Base64.getDecoder().decode(req.getOriginal_pdf_b64());
//            byte[] cmsBytes = Base64.getDecoder().decode(req.getCms_b64());
//
//            if (cmsBytes == null || cmsBytes.length < 200) {
//                throw new IllegalArgumentException("CMS too small - likely not a valid detached signature");
//            }
//
//            System.out.println("Received CMS size: " + cmsBytes.length + " bytes");
//
//            // Load the original PDF
//            PDDocument pdfBoxDoc = PDDocument.load(originalPdfBytes);
//
//            // Find the signature dictionary (the placeholder we created earlier)
//            List<PDSignature> signatures = pdfBoxDoc.getSignatureDictionaries();
//            if (signatures.isEmpty()) {
//                pdfBoxDoc.close();
//                throw new IllegalStateException("No signature field found - preparation step failed. " +
//                        "Make sure the PDF was prepared with a signature placeholder in /prepare.");
//            }
//
//            // Take the last (most recently added) signature
//            PDSignature signature = signatures.get(signatures.size() - 1);
//
//            // IMPORTANT: For detached CMS (PKCS#7/CMS), we set the binary contents directly
//            // No need to convert to hex or pad manually — PDFBox handles the /Contents hex encoding internally
//            signature.setContents(cmsBytes);
//
//            // Optional: Set other properties if not already set during preparation
//            // signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
//            // signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
//
//            // Save as incremental update (very important for digital signatures!)
//            ByteArrayOutputStream baos = new ByteArrayOutputStream();
//            pdfBoxDoc.saveIncremental(baos);
//            pdfBoxDoc.close();
//
//            byte[] signedPdfBytes = baos.toByteArray();
//
//            Map<String, Object> response = new HashMap<>();
//            response.put("signed_pdf_b64", Base64.getEncoder().encodeToString(signedPdfBytes));
//
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            Map<String, Object> error = new HashMap<>();
//            error.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
//            return ResponseEntity.status(500).body(error);
//        }
//    }
//
//    // DTO for the new endpoint
//    public static class EmbedRequest {
//        private String original_pdf_b64;
//        private String cms_b64;
//
//        public String getOriginal_pdf_b64() { return original_pdf_b64; }
//        public void setOriginal_pdf_b64(String original_pdf_b64) { this.original_pdf_b64 = original_pdf_b64; }
//
//        public String getCms_b64() { return cms_b64; }
//        public void setCms_b64(String cms_b64) { this.cms_b64 = cms_b64; }
//    }
//
//    public static class PrepareRequest {
//        private String source_pdf_b64;
//        public String getSource_pdf_b64() { return source_pdf_b64; }
//        public void setSource_pdf_b64(String source_pdf_b64) { this.source_pdf_b64 = source_pdf_b64; }
//    }
//
//    public static class FinalizeRequest {
//        private String source_pdf_b64;
//        private String signature_b64;
//        public String getSource_pdf_b64() { return source_pdf_b64; }
//        public void setSource_pdf_b64(String b64) { this.source_pdf_b64 = b64; }
//        public String getSignature_b64() { return signature_b64; }
//        public void setSignature_b64(String b64) { this.signature_b64 = b64; }
//    }
//}