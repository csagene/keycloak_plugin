package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenValidationResponse implements Serializable {
    private String token;
    private boolean valid;
    private String nuit;
    private String contact;
    private String organicCode;
    private String scope;
    private String bank;
    private String clientId;
    private String generationTimestamp;
    private String message;

    public ScadTokenValidationResponse() {
    }

    public ScadTokenValidationResponse(String token, boolean valid, String nuit, String contact, 
                                        String organicCode, String scope, String bank, String clientId, 
                                        String generationTimestamp, String message) {
        this.token = token;
        this.valid = valid;
        this.nuit = nuit;
        this.contact = contact;
        this.organicCode = organicCode;
        this.scope = scope;
        this.bank = bank;
        this.clientId = clientId;
        this.generationTimestamp = generationTimestamp;
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getOrganicCode() {
        return organicCode;
    }

    public void setOrganicCode(String organicCode) {
        this.organicCode = organicCode;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getBank() {
        return bank;
    }

    public void setBank(String bank) {
        this.bank = bank;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getGenerationTimestamp() {
        return generationTimestamp;
    }

    public void setGenerationTimestamp(String generationTimestamp) {
        this.generationTimestamp = generationTimestamp;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
