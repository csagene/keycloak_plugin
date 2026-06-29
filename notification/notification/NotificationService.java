package mozgif.framework.core.notification;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import mozgif.framework.core.exception.business.NotificationException;
import mozgif.framework.core.notification.email.JavaMailManager;
import mozgif.framework.core.notification.sms.ozeki.OzekiSmsManager;
import mozgif.framework.core.notification.sms.twilio.TwilioSmsManager;

/**
 * Unified notification service. Manages sending messages through different channels (SMS, Email).
 * 
 * * @author Gautchi Rogerio Chambe 
 * * @author António Cuinica
 */
@Component
public class NotificationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

	@Autowired(required = false)
	private JavaMailManager javaMailManager;

	@Autowired(required = false)
	private OzekiSmsManager ozekiSmsManager;

	@Autowired(required = false)
	private TwilioSmsManager twilioSmsManager;

	@Value("${notification.default.max.retries:3}")
	private int defaultMaxRetries;

	@Value("${notification.sms.provider:ozeki}") // options: ozeki, twilio, both
	private String smsProvider;

	/**
	 * Sends SMS using the configured provider.
	 *
	 * @param phoneNumber Recipient number
	 * @param message     Message content
	 * @throws NotificationException If sending fails
	 */
	public void sendSmsMessage(String phoneNumber, String message) throws NotificationException {
		sendSmsMessage(phoneNumber, message, defaultMaxRetries);
	}

	/**
	 * Sends SMS using the configured provider with custom retries.
	 *
	 * @param phoneNumber Recipient number
	 * @param message     Message content
	 * @param maxRetries  Maximum number of retry attempts
	 * @throws NotificationException If sending fails
	 */
	public void sendSmsMessage(String phoneNumber, String message, int maxRetries) throws NotificationException {
		validatePhoneNumber(phoneNumber);
		validateMessage(message);

		LOGGER.info("Sending SMS to {} using provider: {}", phoneNumber, smsProvider);

		try {
			switch (smsProvider.toLowerCase()) {
			case "ozeki":
				sendSmsViaOzeki(phoneNumber, message, maxRetries);
				break;
			case "twilio":
				sendSmsViaTwilio(phoneNumber, message, maxRetries);
				break;
			case "both":
				sendSmsViaBothProviders(phoneNumber, message, maxRetries);
				break;
			default:
				throw new NotificationException("SMS provider not supported: " + smsProvider);
			}
			LOGGER.info("SMS successfully sent to {}", phoneNumber);
		} catch (NotificationException e) {
			LOGGER.error("Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
			throw e;
		} catch (Exception e) {
			LOGGER.error("Unexpected error sending SMS to {}", phoneNumber, e);
			throw new NotificationException("Error sending SMS: " + e.getMessage(), e);
		}
	}

	/**
	 * Sends SMS via Ozeki provider.
	 * 
	 * @throws NotificationException
	 */
	private void sendSmsViaOzeki(String phoneNumber, String message, int maxRetries) throws NotificationException {
		if (ozekiSmsManager == null) {
			throw new NotificationException("OzekiSmsManager is not configured or enabled");
		}
		ozekiSmsManager.send(phoneNumber, message, maxRetries);
	}

	/**
	 * Sends SMS via Twilio provider.
	 * 
	 * @throws NotificationException
	 */
	private void sendSmsViaTwilio(String phoneNumber, String message, int maxRetries) throws NotificationException {
		if (twilioSmsManager == null) {
			throw new NotificationException("TwilioSmsManager is not configured or enabled");
		}
		twilioSmsManager.send(phoneNumber, message, maxRetries);
	}

	/**
	 * Sends SMS via both providers (fallback).
	 * 
	 * @throws NotificationException
	 */
	private void sendSmsViaBothProviders(String phoneNumber, String message, int maxRetries)
			throws NotificationException {
		Exception lastException = null;

		// Tenta Ozeki primeiro
		if (ozekiSmsManager != null) {
			try {
				ozekiSmsManager.send(phoneNumber, message, maxRetries);
				LOGGER.info("SMS successfully sent via Ozeki to {}", phoneNumber);
				return;
			} catch (Exception e) {
				LOGGER.warn("Failed to send via Ozeki for {}, trying Twilio...", phoneNumber);
				lastException = e;
			}
		}

		if (twilioSmsManager != null) {
			try {
				twilioSmsManager.send(phoneNumber, message, maxRetries);
				LOGGER.info("SMS successfully sent via Twilio (fallback) to {}", phoneNumber);
				return;
			} catch (Exception e) {
				LOGGER.error("Failed to send via Twilio to {}", phoneNumber);
				lastException = e;
			}
		}

		throw new NotificationException(
				"Failed to send SMS through all available providers: "
						+ (lastException != null ? lastException.getMessage() : "No provider available"),
				lastException);
	}

	/**
	 * Sends email with custom subject.
	 *
	 * @param email   Email address of recipient
	 * @param subject Email subject
	 * @param message Message content (HTML or text)
	 * @throws NotificationException If sending fails
	 */
	public void sendEmailMessage(String email, String subject, String message) throws NotificationException {
		sendEmailMessage(email, null, subject, message);
	}

	/**
	 * Sends email with CC to other recipients.
	 *
	 * @param email          Email address of primary recipient
	 * @param otherReceivers List of emails for CC
	 * @param subject        Email subject
	 * @param message        Message content (HTML or text)
	 * @throws NotificationException If sending fails
	 */
	public void sendEmailMessage(String email, List<String> otherReceivers, String subject, String message)
			throws NotificationException {

		validateEmail(email);
		validateSubject(subject);
		validateMessage(message);

		if (javaMailManager == null) {
			throw new NotificationException(
					"Email service not available, JavaMailManager is not configured or enabled");
		}

		LOGGER.info("Sending email to {} with subject: {}", email, subject);

		try {
			if (otherReceivers != null && !otherReceivers.isEmpty()) {
				javaMailManager.send(subject, email, otherReceivers, message);
			} else {
				javaMailManager.send(subject, email, message);
			}
			LOGGER.info("Email successfully sent to {}", email);
		} catch (Exception e) {
			LOGGER.error("Unexpected error sending email to {}", email, e);
			throw new NotificationException("Error sending email. Please try again later: " + e.getMessage(), e);
		}
	}

	/**
	 * Sends notification through multiple channels simultaneously with different messages.
	 *
	 * @param phoneNumber  Recipient number (optional)
	 * @param email        Recipient email (optional)
	 * @param subject      Notification subject
	 * @param smsMessage   Message for SMS (plain text)
	 * @param emailMessage Message for Email (can be HTML)
	 */
	public void sendMultiChannelNotification(String phoneNumber, String email, String subject, String smsMessage,
			String emailMessage) {
		sendMultiChannelNotification(phoneNumber, email, subject, smsMessage, emailMessage, defaultMaxRetries);
	}

	/**
	 * Sends notification through multiple channels simultaneously with retries and different messages.
	 *
	 * @param phoneNumber  Recipient number (optional)
	 * @param email        Recipient email (optional)
	 * @param subject      Notification subject
	 * @param smsMessage   Message for SMS (plain text)
	 * @param emailMessage Message for Email (can be HTML)
	 * @param maxRetries   Maximum number of retry attempts
	 */
	public void sendMultiChannelNotification(String phoneNumber, String email, String subject, String smsMessage,
			String emailMessage, int maxRetries) {

		LOGGER.info("Sending multi-channel notification to phone: {}, email: {}", phoneNumber, email);

		if (StringUtils.hasText(phoneNumber)) {
			CompletableFuture.runAsync(() -> {
				try {
					sendSmsMessage(phoneNumber, smsMessage, maxRetries);
				} catch (Exception e) {
					LOGGER.error("Failed to send SMS in multi-channel to {}", phoneNumber, e);
				}
			});
		}

		if (StringUtils.hasText(email)) {
			CompletableFuture.runAsync(() -> {
				try {
					sendEmailMessage(email, subject, emailMessage);
				} catch (Exception e) {
					LOGGER.error("Failed to send email in multi-channel to {}", email, e);
				}
			});
		}

		if (!StringUtils.hasText(phoneNumber) && !StringUtils.hasText(email)) {
			LOGGER.warn("No notification channel provided (phone or email)");
		}
	}

	/**
	 * Sends notification through multiple channels with message transformer. Useful
	 * when you have a base message and want to derive the SMS message from it.
	 *
	 * @param phoneNumber      Recipient number (optional)
	 * @param email            Recipient email (optional)
	 * @param subject          Notification subject
	 * @param baseMessage      Base message that will be used for both channels
	 * @param smsTransformer   Function to transform the base message into SMS (e.g.,
	 *                         remove HTML)
	 * @param emailTransformer Function to transform the base message into Email
	 *                         (optional)
	 */
	public void sendMultiChannelNotificationWithTransformer(String phoneNumber, String email, String subject,
			String baseMessage, Function<String, String> smsTransformer, Function<String, String> emailTransformer) {

		String smsMessage = smsTransformer != null ? smsTransformer.apply(baseMessage) : cleanHtmlForSms(baseMessage);
		String emailMessage = emailTransformer != null ? emailTransformer.apply(baseMessage) : baseMessage;

		sendMultiChannelNotification(phoneNumber, email, subject, smsMessage, emailMessage);
	}

	/**
	 * Sends notification through multiple channels with simple transformer (SMS only).
	 *
	 * @param phoneNumber    Recipient number (optional)
	 * @param email          Recipient email (optional)
	 * @param subject        Notification subject
	 * @param emailMessage   Message for email (can be HTML)
	 * @param smsTransformer Function to transform the email message into SMS
	 */
	public void sendMultiChannelNotificationWithSmsTransformer(String phoneNumber, String email, String subject,
			String emailMessage, Function<String, String> smsTransformer) {

		String smsMessage = smsTransformer != null ? smsTransformer.apply(emailMessage) : cleanHtmlForSms(emailMessage);
		sendMultiChannelNotification(phoneNumber, email, subject, smsMessage, emailMessage);
	}

	/**
	 * Removes HTML tags and cleans the message for sending via SMS.
	 *
	 * @param htmlMessage HTML message
	 * @return Cleaned message for SMS
	 */
	private String cleanHtmlForSms(String htmlMessage) {
		if (htmlMessage == null) {
			return null;
		}
		// Removes HTML tags
		String cleanMessage = htmlMessage.replaceAll("<[^>]*>", "");
		// Removes special characters not supported in SMS
		cleanMessage = cleanMessage.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("&lt;", "<")
				.replaceAll("&gt;", ">").replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
		// Removes multiple spaces and line breaks
		cleanMessage = cleanMessage.replaceAll("\\s+", " ").trim();
		// Limits the size for SMS (160 characters)
		if (cleanMessage.length() > 160) {
			cleanMessage = cleanMessage.substring(0, 157) + "...";
		}
		return cleanMessage;
	}

	/**
	 * Validates the phone number.
	 */
	private void validatePhoneNumber(String phoneNumber) {
		if (!StringUtils.hasText(phoneNumber)) {
			throw new IllegalArgumentException("Phone number cannot be null or empty");
		}
	}

	/**
	 * Validates the email address.
	 */
	private void validateEmail(String email) {
		if (!StringUtils.hasText(email)) {
			throw new IllegalArgumentException("Email cannot be null or empty");
		}
		if (!email.contains("@") || !email.contains(".")) {
			throw new IllegalArgumentException("Invalid email: " + email);
		}
	}

	/**
	 * Validates the subject.
	 */
	private void validateSubject(String subject) {
		if (!StringUtils.hasText(subject)) {
			throw new IllegalArgumentException("Subject cannot be null or empty");
		}
	}

	/**
	 * Validates the message.
	 */
	private void validateMessage(String message) {
		if (!StringUtils.hasText(message)) {
			throw new IllegalArgumentException("Message cannot be null or empty");
		}
	}

	/**
	 * Checks if the SMS service is available.
	 */
	public boolean isSmsAvailable() {
		return ("ozeki".equalsIgnoreCase(smsProvider) && ozekiSmsManager != null)
				|| ("twilio".equalsIgnoreCase(smsProvider) && twilioSmsManager != null)
				|| ("both".equalsIgnoreCase(smsProvider) && (ozekiSmsManager != null || twilioSmsManager != null));
	}

	/**
	 * Checks if the email service is available.
	 */
	public boolean isEmailAvailable() {
		return javaMailManager != null;
	}
}