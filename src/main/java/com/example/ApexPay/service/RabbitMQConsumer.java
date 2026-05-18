package com.example.ApexPay.service;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.event.TransactionSuccessEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RabbitMQConsumer {

    // This annotation tells Spring to listen to this specific queue 24/7
    @RabbitListener(queues = RabbitMQConfig.AI_QUEUE)
    public void consumeMessageFromQueue(TransactionSuccessEvent event) {
        log.info("Message received from AI Queue! Preparing for AI analysis: {}", event);
    }
}