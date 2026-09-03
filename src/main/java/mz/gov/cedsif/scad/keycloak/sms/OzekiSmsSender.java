package mz.gov.cedsif.scad.keycloak.sms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ozeki SMS sender for the SCAD Keycloak plugin.
 * <p>
 * Communicates directly with Ozeki NG via TCP socket using the
 * Ozeki SMS HTTP API protocol — no external SDK required.
 * <p>
 * Used for production SMS delivery. For test environments use
 * {@link TwilioSmsSender} instead.
 *
 * @author SCAD / CEDSIF
 */
public class OzekiSmsSender {

    private static final Logger LOGGER = Logger.getLogger(OzekiSmsSender.class.getName());

    private static final long RETRY_DELAY_MILLIS = 1000L;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int connectionTimeoutMs;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean loggedIn = false;

    private final ReentrantLock lock = new ReentrantLock();

    public OzekiSmsSender(String host, int port, String username, String password, int connectionTimeoutMs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * Sends an SMS via Ozeki with automatic retries.
     *
     * @param receiver   recipient number in E.164 format
     * @param message    message body
     * @param maxRetries maximum number of attempts
     * @throws Exception if all attempts fail
     */
    public void send(String receiver, String message, int maxRetries) throws Exception {
        if (receiver == null || receiver.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient cannot be null or empty");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be null or empty");
        }
        if (maxRetries < 1) {
            throw new IllegalArgumentException("maxRetries must be at least 1");
        }

        int attempts = 1;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            lock.lock();
            try {
                int httpPort = (this.port == 9500) ? 9501 : this.port;
                String encodedMessage = java.net.URLEncoder.encode(message, "UTF-8");
                String urlStr = String.format("http://%s:%d/api?action=sendmessage&username=%s&password=%s&recipient=%s&messagedata=%s",
                        host, httpPort, username, password, receiver, encodedMessage);
                
                String curlCommand = String.format("curl \"%s\"", urlStr);
                LOGGER.info(String.format("Executing Ozeki SMS request: %s", curlCommand));
                
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(connectionTimeoutMs);
                con.setReadTimeout(connectionTimeoutMs);
                
                int responseCode = con.getResponseCode();
                if (responseCode == 200) {
                    LOGGER.info(String.format("Ozeki: SMS sent successfully to %s on attempt %d/%d",
                            receiver, attempts, maxRetries));
                    return;
                } else {
                    throw new IOException("HTTP Response Code: " + responseCode);
                }

            } catch (Exception e) {
                lastException = e;
                loggedIn = false;
                closeQuietly();

                if (attempts >= maxRetries) {
                    LOGGER.log(Level.SEVERE,
                            String.format("Ozeki: Failed to send SMS to %s after %d attempt(s). Cause: %s",
                                    receiver, attempts, e.getMessage()), e);
                    break;
                }

                LOGGER.warning(String.format("Ozeki: Failed to send SMS to %s on attempt %d/%d: %s. Retrying...",
                        receiver, attempts, maxRetries, e.getMessage()));
                attempts++;
            } finally {
                lock.unlock();
            }

            waitBeforeRetry();
        }

        throw new Exception(String.format("Ozeki: SMS to %s failed after %d attempt(s): %s",
                receiver, maxRetries, lastException != null ? lastException.getMessage() : "unknown error"),
                lastException);
    }

    /** Sends with default 3 retries. */
    public void send(String receiver, String message) throws Exception {
        send(receiver, message, 3);
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private void ensureConnected(int attempt) throws IOException, InterruptedException {
        if (!loggedIn || socket == null || socket.isClosed()) {
            LOGGER.fine(String.format("Ozeki: Connecting to %s:%d (attempt %d)...", host, port, attempt));
            connect();
            login();
        }
    }

    private void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectionTimeoutMs);
        socket.setSoTimeout(connectionTimeoutMs);
        out = new PrintWriter(socket.getOutputStream(), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        LOGGER.fine(String.format("Ozeki: TCP connection established to %s:%d", host, port));
    }

    private void login() throws IOException {
        // Ozeki NG text protocol: send LOGIN command, read response
        sendLine("LOGIN " + username + " " + password);
        String response = readLine();
        if (response == null || !response.toUpperCase().contains("OK")) {
            throw new IOException("Ozeki login failed for user '" + username + "'. Server response: " + response);
        }
        loggedIn = true;
        LOGGER.info("Ozeki: Logged in successfully as user: " + username);
    }

    private void sendMessage(String receiver, String messageBody) throws IOException {
        // Ozeki NG text protocol: SEND <receiver> <message>
        sendLine("SEND " + receiver + " " + messageBody);
        String response = readLine();
        if (response == null || !response.toUpperCase().contains("OK")) {
            throw new IOException("Ozeki rejected message to " + receiver + ". Server response: " + response);
        }
        LOGGER.fine("Ozeki: Message accepted for delivery to " + receiver);
    }

    private void sendLine(String line) throws IOException {
        out.println(line);
        out.flush();
    }

    private String readLine() throws IOException {
        return in.readLine();
    }

    private void closeQuietly() {
        try {
            if (out  != null) out.close();
            if (in   != null) in.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) { }
        socket = null;
        out = null;
        in  = null;
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Disconnects from the Ozeki server. */
    public void disconnect() {
        lock.lock();
        try {
            if (loggedIn && out != null) {
                try { sendLine("LOGOUT"); } catch (Exception ignored) { }
            }
            closeQuietly();
            loggedIn = false;
            LOGGER.info("Ozeki: Client disconnected.");
        } finally {
            lock.unlock();
        }
    }

    public boolean isLoggedIn() {
        return loggedIn && socket != null && !socket.isClosed();
    }
}
