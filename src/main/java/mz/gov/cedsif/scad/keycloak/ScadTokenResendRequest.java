package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenResendRequest implements Serializable {
    private String token;

    public ScadTokenResendRequest() {
    }

    public ScadTokenResendRequest(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
