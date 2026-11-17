package com.pedrocmoreira.garage.execution.domain.model;

import com.pedrocmoreira.garage.execution.domain.exception.InvalidStatusTransitionException;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Item da fila de execução (documento MongoDB). O modelo de documento comporta naturalmente
 * listas variáveis de serviços/peças e a linha do tempo de atualizações do mecânico.
 */
@Document("execution_queue")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionJob {
    @Id
    private String id;

    @Indexed(unique = true)
    private Long serviceOrderId;

    private String serviceOrderNumber;
    private String vehiclePlate;
    private String vehicleDescription;
    private String observations;

    @Indexed
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.QUEUED_DIAGNOSIS;

    /** Maior valor = maior prioridade na fila. */
    @Builder.Default
    private int priority = 0;

    @Builder.Default
    private List<String> services = new ArrayList<>();

    @Builder.Default
    private List<PartRequirement> parts = new ArrayList<>();

    private boolean partsReserved;
    private String diagnosisNotes;
    private String cancellationReason;

    @Builder.Default
    private List<TimelineEntry> timeline = new ArrayList<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public record PartRequirement(Long partId, String name, int quantity) {}

    public record TimelineEntry(ExecutionStatus status, String note, Instant at) {}

    public void moveTo(ExecutionStatus next, String note) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(String.format(
                    "Transição inválida na execução da OS %s: %s -> %s", serviceOrderNumber, status, next));
        }
        status = next;
        updatedAt = Instant.now();
        timeline.add(new TimelineEntry(next, note, updatedAt));
    }

    public void registerCreation() {
        timeline.add(new TimelineEntry(status, "OS recebida na fila de diagnóstico", createdAt));
    }
}
