/*
 * Copyright (c) 2003-2026 CEDSIF. All rights reserved.
 */
package mozgif.framework.core.notification.sms.ozeki;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hu.ozeki.OzSMSMessage;
import hu.ozeki.OzSmsClient;

/***
 * Ozeki SMS client for integration with Ozeki NG server.
 * Encapsulates Ozeki client functionality for sending and receiving SMS.
 * 
 * @author Tiago da Fonseca Frazao
 * @author António Cuinica
 */
public class OzekiSms extends OzSmsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OzekiSms.class);

    /**
     * Constructor of Ozeki client.
     *
     * @param host Host of Ozeki server
     * @param port Port of Ozeki server
     * @throws IOException If there is a connection error
     * @throws InterruptedException If the operation is interrupted
     */
    public OzekiSms(final String host, final int port)
            throws IOException, InterruptedException {
        super(host, port);
        LOGGER.debug("Ozeki client initialized for {}:{}", host, port);
    }

    @Override
    public void doOnMessageAcceptedForDelivery(final OzSMSMessage sms) {
        LOGGER.debug("Message accepted for delivery - ID: {}, To: {}, Message: {}", 
                     sms.messageId, sms.receiver, sms.messageData);
    }

    @Override
    public void doOnMessageDeliveredToHandset(final OzSMSMessage sms) {
        LOGGER.info("Message delivered to handset - ID: {}, To: {}", 
                    sms.messageId, sms.receiver);
    }

    @Override
    public void doOnMessageDeliveredToNetwork(final OzSMSMessage sms) {
        LOGGER.info("Message delivered to network - ID: {}, To: {}", 
                    sms.messageId, sms.receiver);
    }

    @Override
    public void doOnMessageDeliveryError(final OzSMSMessage sms) {
        LOGGER.error("Error in message delivery - ID: {}, To: {}, Message: {}", 
                     sms.messageId, sms.receiver, sms.messageData);
    }

    @Override
    public void doOnMessageReceived(final OzSMSMessage sms) {
        LOGGER.info("Message received - From: {}, To: {}, Message: {}", 
                    sms.sender, sms.receiver, sms.messageData);
    }

    @Override
    public void doOnClientConnectionError(final int errorCode, final String errorMessage) {
        LOGGER.error("Connection error with Ozeki client - Code: {}, Message: {}", 
                     errorCode, errorMessage);
    }
}