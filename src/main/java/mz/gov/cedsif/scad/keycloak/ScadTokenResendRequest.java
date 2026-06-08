package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenResendRequest implements Serializable {
    private String nuit;

    public ScadTokenResendRequest() {
    }

    public ScadTokenResendRequest(String nuit) {
        this.nuit = nuit;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }
}
