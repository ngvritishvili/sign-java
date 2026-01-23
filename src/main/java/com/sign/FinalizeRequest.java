package com.sign;

public class FinalizeRequest {
    private String prepared_pdf_b64;
    private String cms_hex;  // Laravel sends hex

    // Getters & setters with @JsonProperty if needed
    public String getPrepared_pdf_b64() { return prepared_pdf_b64; }
    public void setPrepared_pdf_b64(String val) { this.prepared_pdf_b64 = val; }

    public String getCms_hex() { return cms_hex; }
    public void setCms_hex(String val) { this.cms_hex = val; }
}