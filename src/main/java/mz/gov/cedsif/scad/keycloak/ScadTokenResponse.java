package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenResponse implements Serializable {
    private String token;
    private String nuit;
    private String mensagem;

    public ScadTokenResponse() {
    }

    public ScadTokenResponse(String token, String nuit, String mensagem) {
        this.token = token;
        this.nuit = nuit;
        this.mensagem = mensagem;
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

    public String getMensagem() {
        return mensagem;
    }

    public void setMensagem(String mensagem) {
        this.mensagem = mensagem;
    }
}
