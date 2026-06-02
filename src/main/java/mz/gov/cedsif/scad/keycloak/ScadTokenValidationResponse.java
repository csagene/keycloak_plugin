package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenValidationResponse implements Serializable {
    private String token;
    private boolean valido;
    private String nuit;
    private String contacto;
    private String organico;
    private String escopo;
    private String banco;
    private String clientId;
    private String timestampGeracao;
    private String mensagem;

    public ScadTokenValidationResponse() {
    }

    public ScadTokenValidationResponse(String token, boolean valido, String nuit, String contacto, 
                                        String organico, String escopo, String banco, String clientId, 
                                        String timestampGeracao, String mensagem) {
        this.token = token;
        this.valido = valido;
        this.nuit = nuit;
        this.contacto = contacto;
        this.organico = organico;
        this.escopo = escopo;
        this.banco = banco;
        this.clientId = clientId;
        this.timestampGeracao = timestampGeracao;
        this.mensagem = mensagem;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isValido() {
        return valido;
    }

    public void setValido(boolean valido) {
        this.valido = valido;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }

    public String getContacto() {
        return contacto;
    }

    public void setContacto(String contacto) {
        this.contacto = contacto;
    }

    public String getOrganico() {
        return organico;
    }

    public void setOrganico(String organico) {
        this.organico = organico;
    }

    public String getEscopo() {
        return escopo;
    }

    public void setEscopo(String escopo) {
        this.escopo = escopo;
    }

    public String getBanco() {
        return banco;
    }

    public void setBanco(String banco) {
        this.banco = banco;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getTimestampGeracao() {
        return timestampGeracao;
    }

    public void setTimestampGeracao(String timestampGeracao) {
        this.timestampGeracao = timestampGeracao;
    }

    public String getMensagem() {
        return mensagem;
    }

    public void setMensagem(String mensagem) {
        this.mensagem = mensagem;
    }
}
