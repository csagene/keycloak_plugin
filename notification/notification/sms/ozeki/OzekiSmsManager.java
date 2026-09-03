package mozgif.framework.core.notification.sms.ozeki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import mozgif.framework.core.exception.business.NotificationException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 * * @author Gautchi Rogerio Chambe
 * * @author António Cuinica
 * 
 * Ozeki SMS sending manager. Responsible for managing connection, authentication and sending Ozeki SMS messages.
 * 
 */
@Component
@ConditionalOnProperty(name = "ozeki.sms.enabled", havingValue = "true", matchIfMissing = true)
public class OzekiSmsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(OzekiSmsManager.class);
    
    private static final int DEFAULT_TIMEOUT_MILLIS = 1000;
    private static final long RETRY_DELAY_MILLIS = 1000L;

    private final String smsHostName;
    private final int smsPort;
    private final String smsUserName;
    private final String smsPassword;
    private final int connectionTimeout;
    
    private OzekiSms client;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Constructor with dependency injection via application.properties.
     */
    public OzekiSmsManager(
            @Value("${ozeki.sms.host:localhost}") String smsHostName,
            @Value("${ozeki.sms.port:8080}") int smsPort,
            @Value("${ozeki.sms.username}") String smsUserName,
            @Value("${ozeki.sms.password}") String smsPassword,
            @Value("${ozeki.sms.connection.timeout:1000}") int connectionTimeout) {
        
        this.smsHostName = smsHostName;
        this.smsPort = smsPort;
        this.smsUserName = smsUserName;
        this.smsPassword = smsPassword;
        this.connectionTimeout = connectionTimeout;
    }

    /**
     * Sends an SMS message with automatic retries.
     *
     * @param receiver   Recipient number
     * @param message    Message content
     * @param maxRetries Maximum number of retry attempts
     * @throws NotificationException If sending fails after all attempts
     */
    public void send(String receiver, String message, int maxRetries) throws NotificationException {
        validateParameters(receiver, message, maxRetries);
        
        int attempts = 1;
        while (attempts <= maxRetries) {
            lock.lock();
            try {
                // Ensure we use the HTTP API port (usually 9501 if 9500 is specified in config)
                int httpPort = (smsPort == 9500) ? 9501 : smsPort;
                String encodedMessage = java.net.URLEncoder.encode(message, "UTF-8");
                String urlStr = String.format("http://%s:%d/api?action=sendmessage&username=%s&password=%s&recipient=%s&messagedata=%s",
                        smsHostName, httpPort, smsUserName, smsPassword, receiver, encodedMessage);
                
                String curlCommand = String.format("curl \"%s\"", urlStr);
                LOGGER.info("Executing Ozeki SMS request: {}", curlCommand);
                
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(connectionTimeout);
                con.setReadTimeout(connectionTimeout);
                
                int responseCode = con.getResponseCode();
                if (responseCode == 200) {
                    LOGGER.info("SMS successfully sent to {} on attempt {}/{}", 
                               receiver, attempts, maxRetries);
                    return;
                } else {
                    throw new IOException("HTTP Response Code: " + responseCode);
                }
                
            } catch (Exception e) {
                if (attempts >= maxRetries) {
                    String finalErrorMsg = String.format(
                            "Error sending SMS via Ozeki to '%s' after %d attempts. Cause: %s",
                            receiver, attempts, e.getMessage());
                    LOGGER.error(finalErrorMsg, e);
                    throw new NotificationException(DEFAULT_TIMEOUT_MILLIS, finalErrorMsg, e);
                }
                
                LOGGER.warn("Failed to send SMS to {} on attempt {}/{}: {}. Retrying...",
                           receiver, attempts, maxRetries, e.getMessage());
                
                waitBeforeRetry();
                attempts++;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Sends an SMS message with default number of retries (3).
     *
     * @param receiver Recipient number
     * @param message  Message content
     * @throws NotificationException If sending fails after all attempts
     */
    public void send(String receiver, String message) throws NotificationException {
        send(receiver, message, 3);
    }

    /**
     * Ensures that the client is logged into the Ozeki server.
     */
    private void ensureLoggedIn(String receiver, int attempts) throws NotificationException {
        try {
            if (client == null || !client.isLoggedIn()) {
                LOGGER.debug("Establishing login to Ozeki for user: {}", smsUserName);
                performLogin();
                validateConnection();
            }
        } catch (Exception e) {
            throw new NotificationException(DEFAULT_TIMEOUT_MILLIS,
                    String.format("Error logging into Ozeki for user '%s' after %d send attempts to %s.",
                            smsUserName, attempts, receiver), e);
        }
    }

    /**
     * Performs login to the Ozeki server.
     */
    private void performLogin() throws IOException, InterruptedException, NotificationException {
        this.client = new OzekiSms(smsHostName, smsPort);
        this.client.login(smsUserName, smsPassword);
        
        if (!client.isLoggedIn()) {
            String loginErrorMsg = String.format(
                    "Failed to authenticate with Ozeki for user '%s'. " +
                    "Likely cause: invalid username/password or expired session.",
                    smsUserName);
            throw new NotificationException(DEFAULT_TIMEOUT_MILLIS, loginErrorMsg);
        }
        
        LOGGER.info("Ozeki login successful for user: {}", smsUserName);
    }

    /**
     * Validates the connection with the Ozeki server.
     * @throws NotificationException 
     */
    private void validateConnection() throws NotificationException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(smsHostName, smsPort), connectionTimeout);
            LOGGER.debug("Ozeki connection validated successfully at {}:{}", smsHostName, smsPort);
        } catch (IOException e) {
            String errorMsg = String.format(
                    "Unable to connect to Ozeki server at %s:%d. " +
                    "Check that the service is running and accessible.",
                    smsHostName, smsPort);
            LOGGER.error(errorMsg, e);
            throw new NotificationException(DEFAULT_TIMEOUT_MILLIS, errorMsg, e);
        }
    }

    /**
     * Validates the input parameters.
     */
    private void validateParameters(String receiver, String message, int maxRetries) {
        if (receiver == null || receiver.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient cannot be null or empty");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be null or empty");
        }
        if (maxRetries < 1) {
            throw new IllegalArgumentException("Number of attempts must be at least 1");
        }
    }

    /**
     * Waits before a new retry attempt.
     */
    private void waitBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Thread interrupted while waiting between retries");
        }
    }

    /**
     * Checks if the client is connected and logged in.
     *
     * @return true if logged in, false otherwise
     */
    public boolean isLoggedIn() {
        lock.lock();
        try {
            return client != null && client.isLoggedIn();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Disconnects the client from the Ozeki server.
     */
    public void disconnect() {
        lock.lock();
        try {
            if (client != null) {
                client.logout();
                client = null;
                LOGGER.info("Ozeki client disconnected successfully");
            }
        } catch (Exception e) {
            LOGGER.error("Error disconnecting Ozeki client", e);
        } finally {
            lock.unlock();
        }
    }
}
