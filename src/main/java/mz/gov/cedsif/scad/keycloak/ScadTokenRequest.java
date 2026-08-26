package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenRequest implements Serializable {
    private String scope;
    private String nuit;
    private String organicCode;
    private String contact;

    public ScadTokenRequest() {
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }

    public String getOrganicCode() {
        return organicCode;
    }

    public void setOrganicCode(String organicCode) {
        this.organicCode = organicCode;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }
}
