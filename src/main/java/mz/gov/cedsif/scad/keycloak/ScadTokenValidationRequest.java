package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenValidationRequest implements Serializable {
    private String token;

    public ScadTokenValidationRequest() {
    }

    public ScadTokenValidationRequest(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
