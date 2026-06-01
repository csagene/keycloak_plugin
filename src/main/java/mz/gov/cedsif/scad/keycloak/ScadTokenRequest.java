package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenRequest implements Serializable {
    private String escopo;
    private String nuit;
    private String organico;
    private String numeroTelefone;

    public ScadTokenRequest() {
    }

    public String getEscopo() {
        return escopo;
    }

    public void setEscopo(String escopo) {
        this.escopo = escopo;
    }

    public String getNuit() {
        return nuit;
    }

    public void setNuit(String nuit) {
        this.nuit = nuit;
    }

    public String getOrganico() {
        return organico;
    }

    public void setOrganico(String organico) {
        this.organico = organico;
    }

    public String getNumeroTelefone() {
        return numeroTelefone;
    }

    public void setNumeroTelefone(String numeroTelefone) {
        this.numeroTelefone = numeroTelefone;
    }
}
