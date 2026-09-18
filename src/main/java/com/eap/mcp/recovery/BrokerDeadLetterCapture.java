package com.eap.mcp.recovery;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(
        name = "eap.recovery-control.broker-dlq.enabled",
        havingValue = "true")
public class BrokerDeadLetterCapture {

    private final BrokerDeadLetterStore store;

    public BrokerDeadLetterCapture(BrokerDeadLetterStore store) {
        this.store = store;
    }

    @RabbitListener(
            queues = "${eap.recovery-control.broker-dlq.queue:order.dlq}",
            ackMode = "MANUAL")
    public void capture(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            store.capture(message);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException failure) {
            channel.basicNack(deliveryTag, false, true);
            throw failure;
        }
    }
}
