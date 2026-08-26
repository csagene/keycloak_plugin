package mozgif.framework.core.notification.sms.twilio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.twilio.Twilio;
import com.twilio.exception.TwilioException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

import mozgif.framework.core.exception.business.NotificationException;

import java.util.concurrent.locks.ReentrantLock;

/***
 * Twilio SMS sending manager. Responsible for sending SMS messages using the Twilio API.
 * 
 * @author Gautchi Rogerio Chambe
 * @author António Cuinica
 */
@Component
@ConditionalOnProperty(name = "twilio.sms.enabled", havingValue = "true", matchIfMissing = true)
public class TwilioSmsManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(TwilioSmsManager.class);

	private static final long RETRY_DELAY_MILLIS = 1000L;
	private static final int DEFAULT_MAX_RETRIES = 3;

	private final String accountSid;
	private final String authToken;
	private final String msid;

	private boolean initialized = false;
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Constructor with dependency injection via application.properties.
	 */
	public TwilioSmsManager(@Value("${twilio.sms.account.sid}") String accountSid,
			@Value("${twilio.sms.auth.token}") String authToken,
			@Value("${twilio.sms.msid}") String msid) {

		this.accountSid = accountSid;
		this.authToken = authToken;
		this.msid = msid;
	}

	/**
	 * Sends an SMS message with automatic retries.
	 *
	 * @param receiver   Recipient's phone number
	 * @param message    Message content
	 * @param maxRetries Maximum number of attempts
	 * @throws NotificationException If sending fails after all attempts
	 */
	public void send(String receiver, String message, int maxRetries) throws NotificationException {
		validateParameters(receiver, message, maxRetries);

		int attempts = 1;
		while (attempts <= maxRetries) {
			lock.lock();
			try {
				initializeTwilio();
				sendMessage(receiver, message);
				LOGGER.info("SMS via Twilio successfully sent to {} on attempt {}/{}", receiver, attempts,
						maxRetries);
				return;

			} catch (TwilioException e) {
				if (attempts >= maxRetries) {
					String rawMessage = String.format(
							"Error sending SMS via Twilio to '%s' after %d attempts. Cause: %s", receiver, attempts,
							e.getMessage());
					LOGGER.error(rawMessage, e);
					throw new NotificationException(rawMessage, e);
				}

				LOGGER.warn("Failed to send SMS via Twilio to {} on attempt {}/{}: {}. Retrying...",
						receiver, attempts, maxRetries, e.getMessage());
				waitBeforeRetry();
				attempts++;

			} catch (Exception e) {
				if (attempts >= maxRetries) {
					String rawMessage = String.format(
							"Unexpected error sending SMS via Twilio to '%s' after %d attempts. Cause: %s",
							receiver, attempts, e.getMessage());

					LOGGER.error(rawMessage, e);
					throw new NotificationException(rawMessage, e);
				}

				LOGGER.warn("Unexpected error in Twilio for {} on attempt {}/{}: {}. Retrying...", receiver,
						attempts, maxRetries, e.getMessage());
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
	 * @param receiver Recipient's phone number
	 * @param message  Message content
	 * @throws NotificationException If sending fails after all attempts
	 */
	public void send(String receiver, String message) throws NotificationException {
		send(receiver, message, DEFAULT_MAX_RETRIES);
	}

	/**
	 * Initializes the Twilio client (executed only once).
	 * 
	 * @throws NotificationException If initialization fails
	 */
	private void initializeTwilio() throws NotificationException {
		if (!initialized) {
			lock.lock();
			try {
				if (!initialized) {
					try {
						LOGGER.debug("Initializing Twilio client for account: {}", maskAccountSid(accountSid));
						Twilio.init(accountSid, authToken);
						initialized = true;
						LOGGER.info("Twilio client initialized successfully");
					} catch (TwilioException e) {
						String rawMessage = String.format(
								"Failed to initialize Twilio client. Account SID: %s, Error: %s",
								maskAccountSid(accountSid), e.getMessage());
						throw new NotificationException(rawMessage, e);
					}
				}
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * Sends the actual message.
	 * 
	 * @throws TwilioException If sending fails
	 */
	private void sendMessage(String receiver, String message) {
		Message.creator(new PhoneNumber(receiver), msid, message)
				.setRiskCheck("disable")
				.create();

		LOGGER.debug("Message sent via Twilio using msid: {} to {}", msid, receiver);
	}

	/**
	 * Validates input parameters.
	 * 
	 * @throws IllegalArgumentException If parameters are invalid
	 */
	private void validateParameters(String receiver, String message, int maxRetries) {
		if (!StringUtils.hasText(receiver)) {
			throw new IllegalArgumentException("Recipient number cannot be null or empty");
		}

		if (!StringUtils.hasText(message)) {
			throw new IllegalArgumentException("Message cannot be null or empty");
		}

		if (maxRetries < 1) {
			throw new IllegalArgumentException("Number of attempts must be at least 1");
		}

		String cleanedReceiver = receiver.replaceAll("[^0-9+]", "");
		if (cleanedReceiver.length() < 10) {
			LOGGER.warn("Phone number {} appears to be invalid (too short)", receiver);
		}

		if (message.length() > 160) {
			LOGGER.warn("Message has {} characters, it may be sent as multiple SMS", message.length());
		}
	}

	/**
	 * Waits before a new attempt.
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
	 * Returns a user-friendly message based on the Twilio exception.
	 *
	 * @param e Twilio exception
	 * @return User-friendly error message
	 */
	 private String getPrettyMessageForError(TwilioException e) {
        String errorMessage = e.getMessage().toLowerCase();

        if (errorMessage.contains("invalid phone number") || errorMessage.contains("21211")) {
            return "Invalid phone number. Please check the number and try again.";
        }
        if (errorMessage.contains("permission") || errorMessage.contains("21408")) {
            return "Permission denied to send SMS to this number.";
        }
        if (errorMessage.contains("unverified") || errorMessage.contains("21610")) {
            return "Unverified destination number. Please use a verified number (trial account).";
        }
        if (errorMessage.contains("limit") || errorMessage.contains("30007")) {
            return "Message limit exceeded. Please try again later.";
        }
        if (errorMessage.contains("authenticate") || errorMessage.contains("20003")) {
            return "Authentication error. Please contact the administrator.";
        }
        if (errorMessage.contains("not found") || errorMessage.contains("20404")) {
            return "Resource not found. Please check your account settings.";
        }
        if (errorMessage.contains("from number") || errorMessage.contains("21219")) {
            return "Invalid origin number. Please check the sender number.";
        }

        return "Failed to send SMS. Please try again later.";
    }

	/**
	 * Checks if the Twilio client is initialized.
	 *
	 * @return true if initialized, false otherwise
	 */
	public boolean isInitialized() {
		return initialized;
	}

	/**
	 * Reinitializes the Twilio client (useful for testing or credential renewal).
	 */
	public void reinitialize() {
		lock.lock();
		try {
			initialized = false;
			LOGGER.info("Twilio client marked for reinitialization");
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Hides part of the Account SID for secure logging.
	 */
	private String maskAccountSid(String sid) {
		if (sid == null || sid.length() <= 8) {
			return "***";
		}
		return sid.substring(0, 6) + "****" + sid.substring(sid.length() - 4);
	}
}