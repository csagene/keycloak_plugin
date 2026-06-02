package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenRequest implements Serializable {
    private String scope;
    private String nuit;
    private String organic;
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

    public String getOrganic() {
        return organic;
    }

    public void setOrganic(String organic) {
        this.organic = organic;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }
}
