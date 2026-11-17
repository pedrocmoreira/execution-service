package com.pedrocmoreira.garage.execution.infrastructure.messaging;

import com.pedrocmoreira.garage.execution.application.ExecutionCommandHandler;
import com.pedrocmoreira.garage.execution.domain.saga.MessageTypes;
import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutionCommandListener {
    private final ExecutionCommandHandler handler;

    @RabbitListener(queues = MessageTypes.QUEUE_EXECUTION_COMMANDS)
    public void onCommand(SagaMessage command) {
        handler.handle(command);
    }
}
