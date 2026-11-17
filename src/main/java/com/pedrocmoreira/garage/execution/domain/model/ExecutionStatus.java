package com.pedrocmoreira.garage.execution.domain.model;

import java.util.Map;
import java.util.Set;

/** Estados da OS na fila de produção (oficina). */
public enum ExecutionStatus {
    QUEUED_DIAGNOSIS,
    IN_DIAGNOSIS,
    DIAGNOSED,
    AWAITING_REPAIR,
    IN_REPAIR,
    COMPLETED,
    CANCELLED;

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> TRANSITIONS = Map.of(
            QUEUED_DIAGNOSIS, Set.of(IN_DIAGNOSIS, CANCELLED),
            IN_DIAGNOSIS, Set.of(DIAGNOSED, CANCELLED),
            DIAGNOSED, Set.of(AWAITING_REPAIR, CANCELLED),
            AWAITING_REPAIR, Set.of(IN_REPAIR, CANCELLED),
            IN_REPAIR, Set.of(COMPLETED, CANCELLED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransitionTo(ExecutionStatus next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
