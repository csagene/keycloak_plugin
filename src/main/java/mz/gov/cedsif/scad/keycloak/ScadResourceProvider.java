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
        logger.info(String.format("Token generation request received: [Scope: %s, NUIT: %s, OrganicCode: %s, Contact: %s]", 
                request.getScope(), request.getNuit(), request.getOrganicCode(), request.getContact()));

        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Token generation attempt by unauthenticated client.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Client not authenticated via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String clientId = client.getClientId();
        String bankName = clientId.replace("-app", "")
                                  .replace("-client", "")
                                  .replace("-prod", "")
                                  .toLowerCase();

        String scope = request.getScope();
        String operationType = "scad";
        if (scope != null && !scope.trim().isEmpty()) {
            String scopeClean = scope.trim().toLowerCase();
            if (scopeClean.length() >= 4) {
                operationType = scopeClean.substring(0, 4);
            } else {
                operationType = scopeClean;
            }
        }

        String prefix = operationType + bankName;

        String charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        SecureRandom random = new SecureRandom();
        StringBuilder randomPart = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            randomPart.append(charset.charAt(random.nextInt(charset.length())));
        }

        String finalToken = prefix + "-" + randomPart.toString();

        logger.info(String.format("Token successfully generated for bank '%s' [Scope: %s]: %s", 
                bankName, scope, finalToken));

        String contact = request.getContact();
        String maskedContact = contact;
        boolean isEmail = false;

        if (contact != null) {
            contact = contact.trim();
            if (contact.contains("@")) {
                isEmail = true;
                int atIndex = contact.indexOf("@");
                if (atIndex > 2) {
                    maskedContact = contact.charAt(0) + "***" + contact.charAt(atIndex - 1) + contact.substring(atIndex);
                }
            } else {
                if (contact.length() > 6) {
                    maskedContact = contact.substring(0, 5) + "****" + contact.substring(contact.length() - 2);
                }
            }
        }

        if (isEmail) {
            enviarEmail(contact, finalToken, 15);
        } else {
            enviarSMSViaTelerivet(contact, finalToken, 15);
        }

        String deliveryMethod = isEmail ? "email" : "SMS";
        String confirmationMessage = String.format("The token was successfully sent via %s to contact %s", deliveryMethod, maskedContact);

        // Guardar token no SingleUseObjectProvider (Keycloak Cache de Uso Único)
        try {
            org.keycloak.models.SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
            
            String nuitVal = request.getNuit() != null ? request.getNuit().trim() : "";
            if (!nuitVal.isEmpty()) {
                String nuitKey = "scad-nuit-token:" + nuitVal;
                java.util.Map<String, String> oldNuitDetails = singleUseObjects.get(nuitKey);
                if (oldNuitDetails != null) {
                    String oldToken = oldNuitDetails.get("token");
                    if (oldToken != null) {
                        singleUseObjects.remove("scad-token:" + oldToken);
                        logger.info("Revoked previous token for NUIT " + nuitVal + ": " + oldToken);
                    }
                }
            }

            java.util.Map<String, String> tokenDetails = new java.util.HashMap<>();
            tokenDetails.put("nuit", nuitVal);
            tokenDetails.put("contact", request.getContact() != null ? request.getContact() : "");
            tokenDetails.put("organicCode", request.getOrganicCode() != null ? request.getOrganicCode() : "");
            tokenDetails.put("scope", request.getScope() != null ? request.getScope() : "");
            tokenDetails.put("bank", bankName);
            tokenDetails.put("clientId", clientId);
            tokenDetails.put("timestamp", String.valueOf(System.currentTimeMillis()));

            // Token válido por 15 minutos (900 segundos)
            long lifespanSeconds = 900;
            String storeKey = "scad-token:" + finalToken;
            singleUseObjects.put(storeKey, lifespanSeconds, tokenDetails);
            logger.info("Token persisted in SingleUseObjectProvider (Single Use) with key: " + storeKey);

            if (!nuitVal.isEmpty()) {
                String nuitKey = "scad-nuit-token:" + nuitVal;
                java.util.Map<String, String> nuitMapping = new java.util.HashMap<>();
                nuitMapping.put("token", finalToken);
                nuitMapping.put("clientId", clientId);
                singleUseObjects.put(nuitKey, lifespanSeconds, nuitMapping);
                logger.info("NUIT to Token mapping persisted with key: " + nuitKey);
            }
        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "Failed to persist token in SingleUseObjectProvider", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Internal failure persisting the token in the single-use cache\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        ScadTokenResponse response = new ScadTokenResponse(
                finalToken, 
                request.getNuit(),
                confirmationMessage
        );

        return Response.ok(response).build();
    }

    @POST
    @Path("/tokens/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateToken(ScadTokenValidationRequest request) {
        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Token validation attempt by unauthenticated client.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Client not authenticated via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (request == null || request.getToken() == null || request.getToken().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Token not provided\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String token = request.getToken().trim();
        String storeKey = "scad-token:" + token;

        try {
            org.keycloak.models.SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
            
            // Consumes the token atomically (remove returns the data if it existed, null if already consumed or expired)
            java.util.Map<String, String> tokenDetails = singleUseObjects.remove(storeKey);

            if (tokenDetails == null || tokenDetails.isEmpty()) {
                logger.warning("Token is invalid, expired, or already consumed: " + token);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Token invalid, expired, or already consumed\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            logger.info("Token successfully validated and consumed (Single-Use): " + token);

            // Cleanup the NUIT mapping
            String nuit = tokenDetails.get("nuit");
            if (nuit != null && !nuit.trim().isEmpty()) {
                singleUseObjects.remove("scad-nuit-token:" + nuit.trim());
            }

            ScadTokenValidationResponse response = new ScadTokenValidationResponse(
                    token,
                    true,
                    tokenDetails.getOrDefault("nuit", ""),
                    tokenDetails.getOrDefault("contact", ""),
                    tokenDetails.getOrDefault("organicCode", ""),
                    tokenDetails.getOrDefault("scope", ""),
                    tokenDetails.getOrDefault("bank", ""),
                    tokenDetails.getOrDefault("clientId", ""),
                    tokenDetails.getOrDefault("timestamp", ""),
                    "Token validated and consumed successfully. This token cannot be reused."
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "Error validating/removing token from SingleUseObjectProvider", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Internal error processing token validation\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    @POST
    @Path("/tokens/resend")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resendToken(ScadTokenResendRequest request) {
        if (request == null || request.getNuit() == null || request.getNuit().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"NUIT not provided\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String nuit = request.getNuit().trim();
        logger.info(String.format("Resend token request received for NUIT: %s", nuit));

        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Token resend attempt by unauthenticated client.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Client not authenticated via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String nuitKey = "scad-nuit-token:" + nuit;
        try {
            org.keycloak.models.SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
            java.util.Map<String, String> nuitDetails = singleUseObjects.get(nuitKey);

            if (nuitDetails == null || nuitDetails.isEmpty()) {
                logger.warning("Resend attempt failed. No active token found for NUIT: " + nuit);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String token = nuitDetails.get("token");
            if (token == null || token.trim().isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String storeKey = "scad-token:" + token;
            java.util.Map<String, String> tokenDetails = singleUseObjects.get(storeKey);

            if (tokenDetails == null || tokenDetails.isEmpty()) {
                logger.warning("Resend attempt failed. Token mapped to NUIT is invalid or expired in cache: " + token);
                // Clean up stale mapping
                singleUseObjects.remove(nuitKey);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            // Verify if the client calling resend is the same client that generated the token originally
            String originalClientId = tokenDetails.get("clientId");
            String currentClientId = client.getClientId();
            if (originalClientId != null && !originalClientId.equals(currentClientId)) {
                logger.warning(String.format("Unauthorized resend attempt: client '%s' tried to resend token generated by client '%s'", 
                        currentClientId, originalClientId));
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"error\": \"You do not have permission to resend this token\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String contact = tokenDetails.get("contact");

            String maskedContact = contact;
            boolean isEmail = false;

            if (contact != null) {
                contact = contact.trim();
                if (contact.contains("@")) {
                    isEmail = true;
                    int atIndex = contact.indexOf("@");
                    if (atIndex > 2) {
                        maskedContact = contact.charAt(0) + "***" + contact.charAt(atIndex - 1) + contact.substring(atIndex);
                    }
                } else {
                    if (contact.length() > 6) {
                        maskedContact = contact.substring(0, 5) + "****" + contact.substring(contact.length() - 2);
                    }
                }
            }

            String timestampStr = tokenDetails.get("timestamp");
            long remainingMinutes = 15;
            if (timestampStr != null) {
                try {
                    long elapsedMs = System.currentTimeMillis() - Long.parseLong(timestampStr);
                    long elapsedSec = elapsedMs / 1000;
                    long remainingSec = 900 - elapsedSec;
                    if (remainingSec > 0) {
                        remainingMinutes = (remainingSec + 59) / 60;
                    } else {
                        remainingMinutes = 1;
                    }
                } catch (Exception e) {
                    logger.warning("Failed to parse token timestamp: " + timestampStr);
                }
            }

            if (isEmail) {
                enviarEmail(contact, token, remainingMinutes);
            } else {
                enviarSMSViaTelerivet(contact, token, remainingMinutes);
            }

            String deliveryMethod = isEmail ? "email" : "SMS";
            String confirmationMessage = String.format("The token was successfully resent via %s to contact %s (Valid for %d min.)", deliveryMethod, maskedContact, remainingMinutes);

            logger.info(String.format("Token successfully resent for client '%s' and NUIT '%s' to '%s'", 
                    currentClientId, nuit, maskedContact));

            ScadTokenResponse response = new ScadTokenResponse(
                    token, 
                    nuit,
                    confirmationMessage
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "Error resending token", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Internal error processing token resend\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }


    private void enviarSMSViaTelerivet(String telefone, String token, long remainingMinutes) {
        String apiKey = TELERIVET_API_KEY;
        String projectId = TELERIVET_PROJECT_ID;

        if ("SUA_API_KEY_AQUI".equals(apiKey) || "SEU_PROJECT_ID_AQUI".equals(projectId)) {
            logger.warning("TELERIVET: Credenciais nao configuradas. Simulando envio de SMS...");
            logger.info(String.format("SIMULACAO SMS -> Enviado para %s: 'O seu token SCAD e %s. Valido por %d min.'", telefone, token, remainingMinutes));
            return;
        }

        String mensagem = "O seu token SCAD e " + token + ". Valido por " + remainingMinutes + " min.";
        
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

    private void enviarEmail(String email, String token, long remainingMinutes) {
        try {
            org.keycloak.email.EmailSenderProvider emailSender = session.getProvider(org.keycloak.email.EmailSenderProvider.class);
            java.util.Map<String, String> config = session.getContext().getRealm().getSmtpConfig();

            // Se o SMTP não estiver configurado no Realm do Keycloak, simulamos o envio no log
            if (config == null || config.isEmpty() || !config.containsKey("host")) {
                logger.warning("SMTP nao configurado no Keycloak Realm. Simulando envio de Email...");
                logger.info(String.format("SIMULACAO EMAIL -> Enviado para %s: 'O seu token SCAD e %s. Valido por %d min.'", email, token, remainingMinutes));
                return;
            }

            // Executar envio de forma assíncrona para não bloquear a resposta REST
            CompletableFuture.runAsync(() -> {
                try {
                    String subject = "Servico SCAD - Token de Autorizacao";
                    String textBody = "O seu token SCAD e: " + token + ". Valido por: " + remainingMinutes + " min.";
                    emailSender.send(config, 
                                     email, 
                                     subject, 
                                     textBody, 
                                     textBody);
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
