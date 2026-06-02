package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenResponse implements Serializable {
    private String token;
    private String nuit;
    private String message;

    public ScadTokenResponse() {
    }

    public ScadTokenResponse(String token, String nuit, String message) {
        this.token = token;
        this.nuit = nuit;
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
