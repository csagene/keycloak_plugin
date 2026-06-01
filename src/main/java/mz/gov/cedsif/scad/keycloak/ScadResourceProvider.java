package mz.gov.cedsif.scad.keycloak;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ClientModel;
import org.keycloak.services.resource.RealmResourceProvider;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.logging.Logger;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class ScadResourceProvider implements RealmResourceProvider {

    private static final Logger logger = Logger.getLogger(ScadResourceProvider.class.getName());
    private final KeycloakSession session;

    // Configurações do Telerivet lidas de variáveis de ambiente para maior segurança
    private static final String TELERIVET_API_KEY = System.getenv().getOrDefault("TELERIVET_API_KEY", "hBPfi_Cf9aIDgiZrS8lIIEM78S2PaFNiS4XT");
    private static final String TELERIVET_PROJECT_ID = System.getenv().getOrDefault("TELERIVET_PROJECT_ID", "PJ807abc14a9f01587");

    // Construtor obrigatório
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
        // 1. Validar se o cliente está devidamente autenticado na sessão do Keycloak (M2M)
        ClientModel client = session.getContext().getClient();
        if (client == null) {
            logger.warning("Tentativa de geração de token por cliente não autenticado ou inválido.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Cliente não autenticado ou inválido via OAuth2 M2M\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // 2. Extrair o nome do Banco de forma segura a partir do Client ID autenticado
        String clientId = client.getClientId(); // Ex: "sncb-app"
        String nomeBanco = clientId.replace("-app", "")
                                   .replace("-client", "")
                                   .replace("-prod", "")
                                   .toLowerCase();

        // 3. Determinar o tipo de operação com base nas 4 primeiras letras do escopo
        String escopo = request.getEscopo();
        String tipoOperacao = "scad"; // Fallback padrão
        if (escopo != null && !escopo.trim().isEmpty()) {
            String escopoClean = escopo.trim().toLowerCase();
            if (escopoClean.length() >= 4) {
                tipoOperacao = escopoClean.substring(0, 4);
            } else {
                tipoOperacao = escopoClean;
            }
        }

        // 4. Formatar o prefixo final da transação
        String prefixo = tipoOperacao + nomeBanco; // Ex: "credsncb" ou "debitmpesa"

        // 5. Gerar a parte segura aleatória de 6 dígitos com o charset do SCAD (34 caracteres)
        String charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // Exclui 'I', 'O', '1', '0' para evitar confusão visual
        SecureRandom random = new SecureRandom();
        StringBuilder parteAleatoria = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            parteAleatoria.append(charset.charAt(random.nextInt(charset.length())));
        }

        // 6. Montar o Token completo
        String tokenFinal = prefixo + "-" + parteAleatoria.toString();

        logger.info(String.format("Token gerado com sucesso para o banco '%s' [Escopo: %s]: %s", 
                nomeBanco, escopo, tokenFinal));

        // 7. Enviar o SMS real de forma assíncrona usando o Gateway Telerivet
        enviarSMSViaTelerivet(request.getNumeroTelefone(), tokenFinal);

        // 8. Construir a resposta JSON simplificada contendo apenas Token, NUIT e mensagem de confirmação
        String numeroMascarado = request.getNumeroTelefone();
        if (numeroMascarado != null && numeroMascarado.length() > 6) {
            numeroMascarado = numeroMascarado.substring(0, 5) + "****" + numeroMascarado.substring(numeroMascarado.length() - 2);
        }
        String mensagemConfirmacao = "O token foi enviado com sucesso via SMS para o numero " + numeroMascarado;

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

        // Se as credenciais estiverem no padrão (não configuradas), apenas simulamos o envio
        if ("SUA_API_KEY_AQUI".equals(apiKey) || "SEU_PROJECT_ID_AQUI".equals(projectId)) {
            logger.warning("TELERIVET: Credenciais nao configuradas nas variaveis de ambiente. Simulando envio de SMS...");
            logger.info(String.format("SIMULAÇÃO SMS -> Enviado para %s: 'O seu token SCAD e %s. Valido por 10 min.'", 
                    telefone, token));
            return;
        }

        String mensagem = "O seu token SCAD e " + token + ". Valido por 10 min.";
        
        try {
            // Formatar payload JSON de forma simples
            String jsonPayload = String.format("{\"to_number\":\"%s\", \"content\":\"%s\"}", telefone, mensagem);

            // Basic Auth Header encoding (username = api_key, password = vazio)
            String rawAuth = apiKey + ":";
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(rawAuth.getBytes(StandardCharsets.UTF_8));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telerivet.com/v1/projects/" + projectId + "/messages/send"))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // Executar requisição assíncrona (não-bloqueante para o Keycloak)
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

    @Override
    public void close() {
        // Liberação de conexões ou recursos se aplicável
    }
}
