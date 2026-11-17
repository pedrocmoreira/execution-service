package com.pedrocmoreira.garage.execution.infrastructure.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.pedrocmoreira.garage.execution.domain.saga.MessageTypes.*;

/**
 * Topologia RabbitMQ: exchange topic "garage.saga" (comandos e eventos) e DLX para mensagens com falha.
 * O Execution consome os comandos "command.execution.#" enviados pelo orquestrador.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange sagaExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue executionCommandsQueue() {
        return QueueBuilder.durable(QUEUE_EXECUTION_COMMANDS)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_EXECUTION_COMMANDS + ".dlq")
                .build();
    }

    @Bean
    Queue executionCommandsDlq() {
        return QueueBuilder.durable(QUEUE_EXECUTION_COMMANDS + ".dlq").build();
    }

    @Bean
    Binding executionCommandsBinding() {
        return BindingBuilder.bind(executionCommandsQueue()).to(sagaExchange()).with("command.execution.#");
    }

    @Bean
    Binding executionCommandsDlqBinding() {
        return BindingBuilder.bind(executionCommandsDlq()).to(deadLetterExchange()).with(QUEUE_EXECUTION_COMMANDS + ".dlq");
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
