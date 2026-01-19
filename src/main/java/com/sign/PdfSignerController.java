package com.sign;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;

import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Store;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.Security;
import java.util.*;


import static org.bouncycastle.oer.its.ieee1609dot2.basetypes.HashAlgorithm.sha256;

@RestController
public class PdfSignerController {

    // DSS service - initialize once
    private final PAdESService padesService;

    public PdfSignerController() {
        // Register BouncyCastle provider once (best practice)
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        this.padesService = new PAdESService(verifier);
    }

    @GetMapping("/")
    public String home() {
        return "PDF Signer Gateway is running! Use POST /prepare and /finalize endpoints.";
    }

    @PostMapping("/prepare")
    public ResponseEntity<PrepareResponse> prepare(@RequestBody PrepareRequest req) {
        try {
            if (req.source_pdf_b64 == null || req.source_pdf_b64.isBlank()) {
                return ResponseEntity.badRequest().body(null);
            }

            byte[] pdfBytes = Base64.getDecoder().decode(req.source_pdf_b64);

            try (PDDocument doc = PDDocument.load(pdfBytes);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                PDSignature signature = createSignature();
                doc.addSignature(signature);

                ExternalSigningSupport external = doc.saveIncrementalForExternalSigning(baos);

                // Get prepared PDF bytes for DSS
                byte[] preparedPdf = baos.toByteArray();

                // Debug: check if prepared PDF starts with %PDF-
                if (preparedPdf.length > 5 && preparedPdf[0] == '%' && preparedPdf[1] == 'P') {
                    System.out.println("Prepared PDF looks valid (length: " + preparedPdf.length + ")");
                } else {
                    System.out.println("WARNING: Prepared PDF may be invalid (length: " + preparedPdf.length + ")");
                }

                // Create DSS document
                DSSDocument preparedDoc = new InMemoryDocument(preparedPdf, "prepared-invoice.pdf");

                // DSS parameters for PAdES Baseline-B
                PAdESSignatureParameters params = new PAdESSignatureParameters();
                params.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
                params.setDigestAlgorithm(DigestAlgorithm.SHA256);
                // Important: generate TBS without certificate (as UniTool will add it)
                params.setGenerateTBSWithoutCertificate(true);

                // Get the correct digest that UniTool expects!
                ToBeSigned toBeSigned = padesService.getDataToSign(preparedDoc, params);
                byte[] digestBytes = toBeSigned.getBytes();  // This is the one!

                String digestHex = bytesToHex(digestBytes);

                // Debug logs
                System.out.println("DSS-compatible hash sent to UniTool: " + digestHex);
                System.out.println("Digest length: " + digestBytes.length + " bytes (should be 32 for SHA-256)");

                PrepareResponse response = new PrepareResponse();
                response.hash = digestHex;

                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/finalize")
    public ResponseEntity<Map<String, String>> finalize(@RequestBody FinalizeRequest req) {
        try {
            byte[] originalPdfBytes = Base64.getDecoder().decode(req.source_pdf_b64);
            byte[] cmsBytes = hexStringToByteArray(req.cms_hex);

            try (PDDocument doc = PDDocument.load(originalPdfBytes);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                PDSignature signature = createSignature();

                doc.addSignature(signature);

                ExternalSigningSupport external = doc.saveIncrementalForExternalSigning(baos);

                // ------------------- DEBUG & VERIFICATION BLOCK -------------------
                try {
                    // Basic CMS structure check
                    if (cmsBytes.length < 100 || cmsBytes[0] != 0x30) {
                        System.out.println("Invalid CMS start byte: " + String.format("%02x", cmsBytes[0]));
                    }

                    CMSSignedData cms = new CMSSignedData(cmsBytes);
                    SignerInformation signer = (SignerInformation) cms.getSignerInfos().getSigners().iterator().next();

                    // Get certificates from CMS
                    @SuppressWarnings("unchecked")
                    Store<X509CertificateHolder> certs = cms.getCertificates();
                    X509CertificateHolder signingCert = (X509CertificateHolder) certs.getMatches(signer.getSID()).iterator().next();
                    Security.addProvider(new BouncyCastleProvider());
                    // Build verifier from certificate
                    SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder()
                            .setProvider("BC")
                            .build(signingCert);

                    // VERY IMPORTANT: Verify signature BEFORE getting content digest
                    boolean isValid = signer.verify(verifier);
                    System.out.println("CMS Signature Verification: " + (isValid ? "VALID" : "INVALID"));

                    if (isValid) {
                        // Now safe to get the digest that was actually signed
                        byte[] signedDigest = signer.getContentDigest();
                        System.out.println("Signed digest in CMS: " + bytesToHex(signedDigest));

                        // Recompute what PDFBox expected
                        InputStream contentStream = external.getContent(); // fresh stream each time
                        byte[] contentBytes = IOUtils.toByteArray(contentStream);

                        MessageDigest sha256Recompute = MessageDigest.getInstance("SHA-256");
                        byte[] originalDigest = sha256Recompute.digest(contentBytes);

                        System.out.println("Original PDFBox digest: " + bytesToHex(originalDigest));

                        if (Arrays.equals(originalDigest, signedDigest)) {
                            System.out.println("→ DIGESTS MATCH! Signer signed the correct data.");
                        } else {
                            System.out.println("→ DIGESTS DO NOT MATCH! Signer signed wrong data.");
                        }
                    } else {
                        System.out.println("Signature verification failed → invalid CMS or wrong certificate");
                    }

                } catch (Exception debugEx) {
                    debugEx.printStackTrace();
                    System.out.println("CMS debug failed (malformed CMS or no cert?): " + debugEx.getMessage());
                }
                // ------------------- END DEBUG BLOCK -------------------

                // Inject the real signature (this is what matters)
                external.setSignature(cmsBytes);

                byte[] signedPdfBytes = baos.toByteArray();
                String signedPdfBase64 = Base64.getEncoder().encodeToString(signedPdfBytes);

                Map<String, String> result = new HashMap<>();
                result.put("signedPdfBase64", signedPdfBase64);

                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Signing failed"));
        }
    }

    private PDSignature createSignature() {
        PDSignature sig = new PDSignature();
        sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        sig.setName("Nikoloz Gvritishvili");
        sig.setLocation("Tbilisi");
        sig.setReason("Invoice signing");
        sig.setSignDate(Calendar.getInstance());
        return sig;
    }

    // Helpers (same as before)
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // DTOs (update client-side accordingly)
    public static class PrepareRequest {
        public String source_pdf_b64;
    }

    public static class PrepareResponse {
        public String hash;
        // removed preppedPdfBase64
    }

    public static class FinalizeRequest {
        public String source_pdf_b64;   // ← now original PDF base64
        public String cms_hex;
    }
}