package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenResendRequest implements Serializable {
    private String nuit;
    private String organicCode;

    public ScadTokenResendRequest() {
    }

    public ScadTokenResendRequest(String nuit, String organicCode) {
        this.nuit = nuit;
        this.organicCode = organicCode;
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
}
