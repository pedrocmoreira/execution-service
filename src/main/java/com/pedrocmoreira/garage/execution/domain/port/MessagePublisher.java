package com.pedrocmoreira.garage.execution.domain.port;

import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;

/** Porta de saída para publicação de comandos/eventos na mensageria. */
public interface MessagePublisher {
    void publish(SagaMessage message);
}
