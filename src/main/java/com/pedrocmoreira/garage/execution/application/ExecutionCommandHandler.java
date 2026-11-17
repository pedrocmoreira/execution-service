package com.pedrocmoreira.garage.execution.application;

import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.pedrocmoreira.garage.execution.domain.saga.MessageTypes.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionCommandHandler {
    private final ExecutionService executionService;

    public void handle(SagaMessage command) {
        log.info("Comando {} recebido para OS {}", command.type(), command.serviceOrderNumber());
        switch (command.type()) {
            case CMD_EXECUTION_START_DIAGNOSIS -> executionService.enqueue(command);
            case CMD_EXECUTION_RESERVE_PARTS -> executionService.reserveParts(command);
            case CMD_EXECUTION_CANCEL -> executionService.cancel(command);
            default -> log.warn("Comando desconhecido: {}", command.type());
        }
    }
}
