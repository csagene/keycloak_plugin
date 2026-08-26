package mz.gov.cedsif.scad.keycloak;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class ScadResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String PROVIDER_ID = "scad";

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new ScadResourceProvider(session);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(Config.Scope config) {
        // Inicialização de configurações globais se necessário
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Executado após inicialização de todas as fábricas
    }

    @Override
    public void close() {
        // Fecho de ligações de recursos globais
    }
}
