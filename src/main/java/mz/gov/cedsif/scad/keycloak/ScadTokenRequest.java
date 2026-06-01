package mz.gov.cedsif.scad.keycloak;

import java.io.Serializable;

public class ScadTokenRequest implements Serializable {
    private String escopo;
    private String nuit;
    private String organico;
    private String contacto;

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

    public String getContacto() {
        return contacto;
    }

    public void setContacto(String contacto) {
        this.contacto = contacto;
    }
}
