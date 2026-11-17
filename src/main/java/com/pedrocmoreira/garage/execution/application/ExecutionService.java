package com.pedrocmoreira.garage.execution.application;

import com.pedrocmoreira.garage.execution.domain.exception.EntityNotFoundException;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob.PartRequirement;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionStatus;
import com.pedrocmoreira.garage.execution.domain.port.MessagePublisher;
import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;
import com.pedrocmoreira.garage.execution.infrastructure.persistence.ExecutionJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.pedrocmoreira.garage.execution.domain.saga.MessageTypes.*;

/** Gerencia a fila de execução, o diagnóstico e o reparo, comunicando o progresso ao OS Service. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {
    private static final List<ExecutionStatus> ACTIVE = List.of(ExecutionStatus.QUEUED_DIAGNOSIS, ExecutionStatus.IN_DIAGNOSIS,
            ExecutionStatus.DIAGNOSED, ExecutionStatus.AWAITING_REPAIR, ExecutionStatus.IN_REPAIR);

    private final ExecutionJobRepository repository;
    private final InventoryService inventoryService;
    private final MessagePublisher publisher;
    private final MeterRegistry meterRegistry;

    /** Comando {@code command.execution.diagnosis.start}: coloca a OS na fila (idempotente). */
    public ExecutionJob enqueue(SagaMessage command) {
        Optional<ExecutionJob> existing = repository.findByServiceOrderId(command.serviceOrderId());
        if (existing.isPresent()) {
            log.info("OS {} já está na fila", command.serviceOrderNumber());
            return existing.get();
        }
        ExecutionJob job = ExecutionJob.builder()
                .serviceOrderId(command.serviceOrderId())
                .serviceOrderNumber(command.serviceOrderNumber())
                .vehiclePlate(command.dataAsString("vehiclePlate"))
                .vehicleDescription(command.dataAsString("vehicleDescription"))
                .observations(command.dataAsString("observations"))
                .services(serviceNames(command.data().get("services")))
                .parts(parts(command.data().get("parts")))
                .build();
        job.registerCreation();
        meterRegistry.counter("garage.execution.enqueued").increment();
        return repository.save(job);
    }

    public ExecutionJob startDiagnosis(Long serviceOrderId) {
        ExecutionJob job = find(serviceOrderId);
        job.moveTo(ExecutionStatus.IN_DIAGNOSIS, "Diagnóstico iniciado");
        ExecutionJob saved = repository.save(job);
        publish(EVT_DIAGNOSIS_STARTED, saved, Map.of());
        return saved;
    }

    public ExecutionJob completeDiagnosis(Long serviceOrderId, String notes) {
        ExecutionJob job = find(serviceOrderId);
        job.setDiagnosisNotes(notes);
        job.moveTo(ExecutionStatus.DIAGNOSED, "Diagnóstico concluído");
        ExecutionJob saved = repository.save(job);
        publish(EVT_DIAGNOSIS_COMPLETED, saved, notes == null ? Map.of() : Map.of("notes", notes));
        return saved;
    }

    /** Comando {@code command.execution.parts.reserve}: após o pagamento, reserva as peças e libera para reparo. */
    public ExecutionJob reserveParts(SagaMessage command) {
        ExecutionJob job = find(command.serviceOrderId());
        if (job.isPartsReserved()) {
            publish(EVT_PARTS_RESERVED, job, Map.of());
            return job;
        }
        Optional<String> failure = inventoryService.reserveAll(job.getParts());
        if (failure.isPresent()) {
            log.warn("Reserva de peças falhou para OS {}: {}", job.getServiceOrderNumber(), failure.get());
            meterRegistry.counter("garage.execution.parts.reservation", "result", "failed").increment();
            publish(EVT_PARTS_RESERVATION_FAILED, job, Map.of("reason", failure.get()));
            return job;
        }
        job.setPartsReserved(true);
        job.moveTo(ExecutionStatus.AWAITING_REPAIR, "Pagamento confirmado e peças reservadas");
        ExecutionJob saved = repository.save(job);
        meterRegistry.counter("garage.execution.parts.reservation", "result", "reserved").increment();
        publish(EVT_PARTS_RESERVED, saved, Map.of());
        return saved;
    }

    public ExecutionJob startRepair(Long serviceOrderId) {
        ExecutionJob job = find(serviceOrderId);
        job.moveTo(ExecutionStatus.IN_REPAIR, "Reparo iniciado");
        return repository.save(job);
    }

    public ExecutionJob completeRepair(Long serviceOrderId) {
        ExecutionJob job = find(serviceOrderId);
        job.moveTo(ExecutionStatus.COMPLETED, "Reparo concluído");
        inventoryService.consumeAll(job.getParts());
        ExecutionJob saved = repository.save(job);
        meterRegistry.counter("garage.execution.completed").increment();
        publish(EVT_EXECUTION_COMPLETED, saved, Map.of());
        return saved;
    }

    /** Compensação {@code command.execution.cancel}: devolve peças reservadas e retira a OS da fila. */
    public void cancel(SagaMessage command) {
        repository.findByServiceOrderId(command.serviceOrderId()).ifPresentOrElse(job -> {
            if (job.getStatus().isFinal()) {
                log.info("Execução da OS {} já finalizada ({}), nada a compensar", job.getServiceOrderNumber(), job.getStatus());
                return;
            }
            if (job.isPartsReserved()) {
                inventoryService.releaseAll(job.getParts());
                job.setPartsReserved(false);
            }
            job.setCancellationReason(command.dataAsString("reason"));
            job.moveTo(ExecutionStatus.CANCELLED, "Cancelada (compensação): " + command.dataAsString("reason"));
            repository.save(job);
            meterRegistry.counter("garage.execution.cancelled").increment();
            publish(EVT_EXECUTION_CANCELLED, job, Map.of());
        }, () -> log.warn("Cancelamento para OS {} que não está na fila", command.serviceOrderNumber()));
    }

    public ExecutionJob updatePriority(Long serviceOrderId, int priority) {
        ExecutionJob job = find(serviceOrderId);
        job.setPriority(priority);
        return repository.save(job);
    }

    public List<ExecutionJob> queue() {
        return repository.findByStatusInOrderByPriorityDescCreatedAtAsc(ACTIVE);
    }

    public List<ExecutionJob> list(ExecutionStatus status) {
        return status == null ? repository.findAll() : repository.findByStatus(status);
    }

    public ExecutionJob find(Long serviceOrderId) {
        return repository.findByServiceOrderId(serviceOrderId)
                .orElseThrow(() -> new EntityNotFoundException("Execução da OS", serviceOrderId));
    }

    private void publish(String type, ExecutionJob job, Map<String, Object> data) {
        publisher.publish(SagaMessage.of(type, job.getServiceOrderId(), job.getServiceOrderNumber(), new HashMap<>(data)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> serviceNames(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        return new ArrayList<>(((List<Map<String, Object>>) list).stream().map(m -> String.valueOf(m.get("name"))).toList());
    }

    @SuppressWarnings("unchecked")
    private static List<PartRequirement> parts(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        return new ArrayList<>(((List<Map<String, Object>>) list).stream()
                .map(m -> new PartRequirement(Long.valueOf(m.get("partId").toString()), String.valueOf(m.get("name")),
                        Integer.parseInt(m.get("quantity").toString())))
                .toList());
    }
}
