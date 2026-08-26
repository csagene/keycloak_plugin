package mozgif.framework.core.notification.email;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 
 * * @author António Cuinica
 * Componente responsável pelo envio de e-mails utilizando JavaMail.
 */
@Component
@ConditionalOnProperty(name = "mail.smtp.enabled", havingValue = "true", matchIfMissing = true)
public class JavaMailManager {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    /**
     * Construtor com injeção de dependências.
     * Configura o JavaMailSender com as propriedades definidas no application.properties
     */
    public JavaMailManager(
            @Value("${mail.smtp.host}") String mailSmtpHost,
            @Value("${mail.smtp.port}") int mailSmtpPort,
            @Value("${mail.smtp.user}") String mailSmtpUser,
            @Value("${mail.smtp.password}") String mailSmtpPassword,
            @Value("${mail.smtp.debug:false}") boolean mailSmtpDebug,
            @Value("${mail.smtp.tls.enabled:true}") boolean mailSmtpTls,
            @Value("${mail.smtp.ssl.enabled:false}") boolean mailSmtpSslEnabled,
            @Value("${mail.smtp.ssl.trust:*}") String mailSmtpSslTrust,
            @Value("${mail.smtp.ssl.checkserveridentity:false}") boolean mailSmtpCheckServerIdentity,
            @Value("${mail.smtp.from.address}") String mailFromAddress) {
        
        this.fromAddress = mailFromAddress;
        this.mailSender = createMailSender(
                mailSmtpHost, mailSmtpPort, mailSmtpUser, mailSmtpPassword,
                mailSmtpDebug, mailSmtpTls, mailSmtpSslEnabled,
                mailSmtpSslTrust, mailSmtpCheckServerIdentity
        );
    }

    /**
     * Cria e configura o JavaMailSender com as propriedades fornecidas.
     */
    private JavaMailSender createMailSender(
            String host, int port, String username, String password,
            boolean debug, boolean tlsEnabled, boolean sslEnabled,
            String sslTrust, boolean checkServerIdentity) {
        
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.transport.protocol", "smtp");
        props.put("mail.smtp.auth", true);
        props.put("mail.smtp.starttls.enable", tlsEnabled);
        props.put("mail.smtp.ssl.enable", sslEnabled);
        props.put("mail.smtp.debug", debug);
        props.put("mail.smtp.ssl.trust", sslTrust);
        props.put("mail.smtp.ssl.checkserveridentity", checkServerIdentity);
        
        return sender;
    }

    /**
     * Envia um e-mail simples para um único destinatário.
     *
     * @param subject  Assunto do e-mail
     * @param receiver Endereço de e-mail do destinatário principal
     * @param message  Corpo da mensagem (suporta HTML)
     * @throws EmailSendingException se ocorrer erro ao enviar o e-mail
     */
    public void send(String subject, String receiver, String message) {
        validateParameters(subject, receiver, message);
        
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, 
                    true, 
                    StandardCharsets.UTF_8.name()
            );
            
            configureMessageHelper(helper, subject, message);
            helper.setTo(receiver);
            
            mailSender.send(mimeMessage);
        } catch (MailException e) {
            throw new EmailSendingException("Failed to send email to: " + receiver, e);
        } catch (Exception e) {
            throw new EmailSendingException("Failed to prepare email for: " + receiver, e);
        }
    }

    /**
     * Envia um e-mail para um destinatário principal e cópias para outros.
     *
     * @param subject        Assunto do e-mail
     * @param receiver       Endereço de e-mail do destinatário principal
     * @param otherReceivers Lista de endereços para cópia (CC)
     * @param message        Corpo da mensagem (suporta HTML)
     * @throws EmailSendingException se ocorrer erro ao enviar o e-mail
     */
    public void send(String subject, String receiver, List<String> otherReceivers, String message) {
        validateParameters(subject, receiver, message);
        
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, 
                    true, 
                    StandardCharsets.UTF_8.name()
            );
            
            configureMessageHelper(helper, subject, message);
            helper.setTo(receiver);
            
            if (hasValidReceivers(otherReceivers)) {
                helper.setCc(otherReceivers.toArray(new String[0]));
            }
            
            mailSender.send(mimeMessage);
        } catch (MailException e) {
            throw new EmailSendingException("Failed to send email to: " + receiver, e);
        } catch (Exception e) {
            throw new EmailSendingException("Failed to prepare email for: " + receiver, e);
        }
    }

    /**
     * Configura o helper da mensagem com propriedades comuns.
     */
    private void configureMessageHelper(MimeMessageHelper helper, String subject, String message) 
            throws Exception {
        helper.setSubject(subject);
        helper.setFrom(fromAddress);
        helper.setText(message, true); // true = suporta HTML
    }

    /**
     * Valida os parâmetros obrigatórios.
     */
    private void validateParameters(String subject, String receiver, String message) {
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("Subject cannot be null or empty");
        }
        if (!StringUtils.hasText(receiver)) {
            throw new IllegalArgumentException("Receiver cannot be null or empty");
        }
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("Message cannot be null or empty");
        }
    }

    /**
     * Verifica se há destinatários válidos na lista.
     */
    private boolean hasValidReceivers(List<String> receivers) {
        return receivers != null && !receivers.isEmpty() 
                && receivers.stream().anyMatch(StringUtils::hasText);
    }

    /**
     * Exceção personalizada para erros de envio de e-mail.
     */
    public static class EmailSendingException extends RuntimeException {
        public EmailSendingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}