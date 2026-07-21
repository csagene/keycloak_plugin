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

    private static final String TWILIO_ACCOUNT_SID = System.getenv().getOrDefault("TWILIO_ACCOUNT_SID", "");
    private static final String TWILIO_AUTH_TOKEN = System.getenv().getOrDefault("TWILIO_AUTH_TOKEN", "");
    private static final String TWILIO_MSID = System.getenv().getOrDefault("TWILIO_MSID", "");

    private static final String SMTP_HOST = System.getenv().getOrDefault("SCAD_SMTP_HOST", "172.17.1.23");
    private static final String SMTP_PORT = System.getenv().getOrDefault("SCAD_SMTP_PORT", "587");
    private static final String SMTP_USER = System.getenv().getOrDefault("SCAD_SMTP_USER", "scad.teste@rsig.gov.mz");
    private static final String SMTP_PASSWORD = System.getenv().getOrDefault("SCAD_SMTP_PASSWORD", "Passw0rd");
    private static final String SMTP_STARTTLS = System.getenv().getOrDefault("SCAD_SMTP_STARTTLS", "true");
    private static final String SMTP_AUTH = System.getenv().getOrDefault("SCAD_SMTP_AUTH", "true");
    private static final String SMTP_SSL_CHECK_SERVER_IDENTITY = System.getenv().getOrDefault("SCAD_SMTP_SSL_CHECK_SERVER_IDENTITY", "false");

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
            enviarEmail(contact, finalToken, 15, false);
        } else {
            enviarSMSViaTwilio(contact, finalToken, 15, false);
        }

        String deliveryMethod = isEmail ? "email" : "SMS";
        String confirmationMessage = String.format("The token was successfully sent via %s to contact %s", deliveryMethod, maskedContact);

        // Guardar token no SingleUseObjectProvider (Keycloak Cache de Uso Único)
        try {
            org.keycloak.models.SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
            
            String nuitVal = request.getNuit() != null ? request.getNuit().trim() : "";
            String organicVal = request.getOrganicCode() != null ? request.getOrganicCode().trim() : "";
            
            if (!nuitVal.isEmpty() && !organicVal.isEmpty()) {
                String nuitOrganicKey = "scad-nuit-organic-token:" + nuitVal + ":" + organicVal;
                java.util.Map<String, String> oldDetails = singleUseObjects.get(nuitOrganicKey);
                if (oldDetails != null) {
                    String oldToken = oldDetails.get("token");
                    if (oldToken != null) {
                        singleUseObjects.remove("scad-token:" + oldToken);
                        logger.info("Revoked previous token for NUIT " + nuitVal + " and organicCode " + organicVal + ": " + oldToken);
                    }
                }
            }

            java.util.Map<String, String> tokenDetails = new java.util.HashMap<>();
            tokenDetails.put("nuit", nuitVal);
            tokenDetails.put("contact", request.getContact() != null ? request.getContact() : "");
            tokenDetails.put("organicCode", organicVal);
            tokenDetails.put("scope", request.getScope() != null ? request.getScope() : "");
            tokenDetails.put("bank", bankName);
            tokenDetails.put("clientId", clientId);
            tokenDetails.put("timestamp", String.valueOf(System.currentTimeMillis()));

            // Token válido por 15 minutos (900 segundos)
            long lifespanSeconds = 900;
            String storeKey = "scad-token:" + finalToken;
            singleUseObjects.put(storeKey, lifespanSeconds, tokenDetails);
            logger.info("Token persisted in SingleUseObjectProvider (Single Use) with key: " + storeKey);

            if (!nuitVal.isEmpty() && !organicVal.isEmpty()) {
                String nuitOrganicKey = "scad-nuit-organic-token:" + nuitVal + ":" + organicVal;
                java.util.Map<String, String> mapping = new java.util.HashMap<>();
                mapping.put("token", finalToken);
                mapping.put("clientId", clientId);
                singleUseObjects.put(nuitOrganicKey, lifespanSeconds, mapping);
                logger.info("NUIT and organicCode to Token mapping persisted with key: " + nuitOrganicKey);
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

            // Cleanup the NUIT and organicCode mapping
            String nuit = tokenDetails.get("nuit");
            String organicCode = tokenDetails.get("organicCode");
            if (nuit != null && !nuit.trim().isEmpty() && organicCode != null && !organicCode.trim().isEmpty()) {
                String nuitOrganicKey = "scad-nuit-organic-token:" + nuit.trim() + ":" + organicCode.trim();
                singleUseObjects.remove(nuitOrganicKey);
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
        if (request == null || request.getNuit() == null || request.getNuit().trim().isEmpty()
                || request.getOrganicCode() == null || request.getOrganicCode().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"NUIT and organicCode must be provided\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String nuit = request.getNuit().trim();
        String organicCode = request.getOrganicCode().trim();
        logger.info(String.format("Resend token request received for NUIT: %s and organicCode: %s", nuit, organicCode));

        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Token resend attempt by unauthenticated client.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Client not authenticated via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String nuitOrganicKey = "scad-nuit-organic-token:" + nuit + ":" + organicCode;
        try {
            org.keycloak.models.SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
            java.util.Map<String, String> mappingDetails = singleUseObjects.get(nuitOrganicKey);

            if (mappingDetails == null || mappingDetails.isEmpty()) {
                logger.warning("Resend attempt failed. No active token found for NUIT: " + nuit + " and organicCode: " + organicCode);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT and organicCode\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String token = mappingDetails.get("token");
            if (token == null || token.trim().isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT and organicCode\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String storeKey = "scad-token:" + token;
            java.util.Map<String, String> tokenDetails = singleUseObjects.get(storeKey);

            if (tokenDetails == null || tokenDetails.isEmpty()) {
                logger.warning("Resend attempt failed. Token mapped to NUIT/organicCode is invalid or expired in cache: " + token);
                // Clean up stale mapping
                singleUseObjects.remove(nuitOrganicKey);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"No active token found for the provided NUIT and organicCode\"}")
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
                enviarEmail(contact, token, remainingMinutes, true);
            } else {
                enviarSMSViaTwilio(contact, token, remainingMinutes, true);
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


    private void enviarSMSViaTwilio(String telefone, String token, long remainingMinutes, boolean isResend) {
        String prefix = isResend ? "Token reenviado. " : "";
        String mensagem = prefix + "O seu código de validação para confirmar a solicitação de crédito é: " + token + ". Este código é válido por " + remainingMinutes + " minutos. Não o partilhe com ninguém. Se não efetuou esta solicitação, ignore esta mensagem.";
        
        // Ensure the phone number is in E.164 format (starts with '+' and country code)
        String formattedTelefone = telefone != null ? telefone.trim() : "";
        if (formattedTelefone.length() == 9 && formattedTelefone.matches("^[8|2]\\d{8}$")) {
            formattedTelefone = "+258" + formattedTelefone;
        } else if (!formattedTelefone.startsWith("+")) {
            formattedTelefone = "+" + formattedTelefone;
        }
        
        final String finalTelefone = formattedTelefone;

        try {
            String urlParameters = "To=" + java.net.URLEncoder.encode(finalTelefone, StandardCharsets.UTF_8.name())
                    + "&MessagingServiceSid=" + java.net.URLEncoder.encode(TWILIO_MSID, StandardCharsets.UTF_8.name())
                    + "&Body=" + java.net.URLEncoder.encode(mensagem, StandardCharsets.UTF_8.name());

            String rawAuth = TWILIO_ACCOUNT_SID + ":" + TWILIO_AUTH_TOKEN;
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(rawAuth.getBytes(StandardCharsets.UTF_8));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + TWILIO_ACCOUNT_SID + "/Messages.json"))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(urlParameters))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("TWILIO: SMS enviado com sucesso para " + finalTelefone + " | Status: " + response.statusCode());
                        } else {
                            logger.severe("TWILIO: Falha ao enviar SMS para " + finalTelefone + ". Status: " + response.statusCode() + " | Resposta: " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        logger.log(java.util.logging.Level.SEVERE, "TWILIO: Erro na conexão com a API", ex);
                        return null;
                    });

        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "TWILIO: Falha ao construir chamada de SMS", e);
        }
    }

    private void enviarEmail(String email, String token, long remainingMinutes, boolean isResend) {
        String prefix = isResend ? "Token reenviado. " : "";
        String subject = isResend ? "Serviço SCAD - Reenvio de Token de Autorização" : "Serviço SCAD - Token de Autorização";
        String textBody = prefix + "O seu código de validação para confirmar a solicitação de crédito é: " + token + ". Este código é válido por " + remainingMinutes + " minutos. Não o partilhe com ninguém. Se não efetuou esta solicitação, ignore esta mensagem.";

        logger.info(String.format("Iniciando envio de email para %s (Assunto: %s)", email, subject));

        CompletableFuture.runAsync(() -> {
            try {
                java.util.Properties prop = new java.util.Properties();
                prop.put("mail.smtp.host", SMTP_HOST);
                prop.put("mail.smtp.port", SMTP_PORT);
                prop.put("mail.smtp.auth", SMTP_AUTH);
                prop.put("mail.smtp.starttls.enable", SMTP_STARTTLS);
                prop.put("mail.smtp.ssl.trust", "*");
                prop.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
                prop.put("mail.smtp.ssl.checkserveridentity", SMTP_SSL_CHECK_SERVER_IDENTITY);

                jakarta.mail.Session mailSession = jakarta.mail.Session.getInstance(prop, new jakarta.mail.Authenticator() {
                    @Override
                    protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new jakarta.mail.PasswordAuthentication(SMTP_USER, SMTP_PASSWORD);
                    }
                });

                jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(mailSession);
                message.setFrom(new jakarta.mail.internet.InternetAddress(SMTP_USER));
                message.setRecipients(jakarta.mail.Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(email));
                message.setSubject(subject, "UTF-8");
                message.setText(textBody, "UTF-8");

                jakarta.mail.Transport.send(message);
                logger.info("EMAIL: Token enviado com sucesso via SMTP direto para " + email);
            } catch (Exception e) {
                logger.log(java.util.logging.Level.SEVERE, "EMAIL: Falha ao enviar email via SMTP direto", e);
            }
        });
    }

    @Override
    public void close() {
    }
}
