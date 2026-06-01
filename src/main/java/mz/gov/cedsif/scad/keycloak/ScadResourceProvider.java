package mz.gov.cedsif.scad.keycloak;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ClientModel;
import org.keycloak.services.resource.RealmResourceProvider;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class ScadResourceProvider implements RealmResourceProvider {

    private static final Logger logger = Logger.getLogger(ScadResourceProvider.class.getName());
    private final KeycloakSession session;

    private static final String TELERIVET_API_KEY = System.getenv().getOrDefault("TELERIVET_API_KEY", "hBPfi_Cf9aIDgiZrS8lIIEM78S2PaFNiS4XT");
    private static final String TELERIVET_PROJECT_ID = System.getenv().getOrDefault("TELERIVET_PROJECT_ID", "PJ807abc14a9f01587");

    public ScadResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @POST
    @Path("/tokens/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response generateToken(ScadTokenRequest request) {
        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Tentativa de geracao de token por cliente nao autenticado.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Cliente nao autenticado via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String clientId = client.getClientId();
        String nomeBanco = clientId.replace("-app", "")
                                   .replace("-client", "")
                                   .replace("-prod", "")
                                   .toLowerCase();

        String escopo = request.getEscopo();
        String tipoOperacao = "scad";
        if (escopo != null && !escopo.trim().isEmpty()) {
            String escopoClean = escopo.trim().toLowerCase();
            if (escopoClean.length() >= 4) {
                tipoOperacao = escopoClean.substring(0, 4);
            } else {
                tipoOperacao = escopoClean;
            }
        }

        String prefixo = tipoOperacao + nomeBanco;

        String charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        SecureRandom random = new SecureRandom();
        StringBuilder parteAleatoria = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            parteAleatoria.append(charset.charAt(random.nextInt(charset.length())));
        }

        String tokenFinal = prefixo + "-" + parteAleatoria.toString();

        logger.info(String.format("Token gerado com sucesso para o banco '%s' [Escopo: %s]: %s", 
                nomeBanco, escopo, tokenFinal));

        String contacto = request.getContacto();
        String contactoMascarado = contacto;
        boolean isEmail = false;

        if (contacto != null) {
            contacto = contacto.trim();
            if (contacto.contains("@")) {
                isEmail = true;
                int atIndex = contacto.indexOf("@");
                if (atIndex > 2) {
                    contactoMascarado = contacto.charAt(0) + "***" + contacto.charAt(atIndex - 1) + contacto.substring(atIndex);
                }
            } else {
                if (contacto.length() > 6) {
                    contactoMascarado = contacto.substring(0, 5) + "****" + contacto.substring(contacto.length() - 2);
                }
            }
        }

        if (isEmail) {
            enviarEmail(contacto, tokenFinal);
        } else {
            enviarSMSViaTelerivet(contacto, tokenFinal);
        }

        String meioEnvio = isEmail ? "email" : "SMS";
        String mensagemConfirmacao = String.format("O token foi enviado com sucesso via %s para o contacto %s", meioEnvio, contactoMascarado);

        ScadTokenResponse response = new ScadTokenResponse(
                tokenFinal, 
                request.getNuit(),
                mensagemConfirmacao
        );

        return Response.ok(response).build();
    }

    private void enviarSMSViaTelerivet(String telefone, String token) {
        String apiKey = TELERIVET_API_KEY;
        String projectId = TELERIVET_PROJECT_ID;

        if ("SUA_API_KEY_AQUI".equals(apiKey) || "SEU_PROJECT_ID_AQUI".equals(projectId)) {
            logger.warning("TELERIVET: Credenciais nao configuradas. Simulando envio de SMS...");
            logger.info(String.format("SIMULACAO SMS -> Enviado para %s: 'O seu token SCAD e %s.'", telefone, token));
            return;
        }

        String mensagem = "O seu token SCAD e " + token + ". Valido por 10 min.";
        
        try {
            String jsonPayload = String.format("{\"to_number\":\"%s\", \"content\":\"%s\"}", telefone, mensagem);
            String rawAuth = apiKey + ":";
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(rawAuth.getBytes(StandardCharsets.UTF_8));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telerivet.com/v1/projects/" + projectId + "/messages/send"))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("TELERIVET: SMS enviado com sucesso para " + telefone + " | Status: " + response.statusCode());
                        } else {
                            logger.severe("TELERIVET: Falha ao enviar SMS. Status: " + response.statusCode() + " | Resposta: " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        logger.log(java.util.logging.Level.SEVERE, "TELERIVET: Erro na conexao com a API", ex);
                        return null;
                    });

        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "TELERIVET: Falha ao construir chamada de SMS", e);
        }
    }

    private void enviarEmail(String email, String token) {
        try {
            org.keycloak.email.EmailSenderProvider emailSender = session.getProvider(org.keycloak.email.EmailSenderProvider.class);
            java.util.Map<String, String> config = session.getContext().getRealm().getSmtpConfig();

            // Se o SMTP não estiver configurado no Realm do Keycloak, simulamos o envio no log
            if (config == null || config.isEmpty() || !config.containsKey("host")) {
                logger.warning("SMTP nao configurado no Keycloak Realm. Simulando envio de Email...");
                logger.info(String.format("SIMULACAO EMAIL -> Enviado para %s: 'O seu token SCAD e %s.'", email, token));
                return;
            }

            // Executar envio de forma assíncrona para não bloquear a resposta REST
            CompletableFuture.runAsync(() -> {
                try {
                    emailSender.send(config, 
                                     email, 
                                     "Servico SCAD - Token de Autorizacao", 
                                     "O seu token SCAD e: " + token, 
                                     "O seu token SCAD e: " + token);
                    logger.info("EMAIL: Token enviado com sucesso via SMTP do Keycloak para " + email);
                } catch (Exception e) {
                    logger.log(java.util.logging.Level.SEVERE, "EMAIL: Falha ao enviar email via SMTP", e);
                }
            });

        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "EMAIL: Falha ao inicializar servico de email do Keycloak", e);
        }
    }

    @Override
    public void close() {
    }
}
