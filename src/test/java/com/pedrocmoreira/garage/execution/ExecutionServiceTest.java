package com.pedrocmoreira.garage.execution;

import com.pedrocmoreira.garage.execution.application.ExecutionService;
import com.pedrocmoreira.garage.execution.application.InventoryService;
import com.pedrocmoreira.garage.execution.domain.exception.EntityNotFoundException;
import com.pedrocmoreira.garage.execution.domain.exception.InvalidStatusTransitionException;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionStatus;
import com.pedrocmoreira.garage.execution.domain.port.MessagePublisher;
import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;
import com.pedrocmoreira.garage.execution.infrastructure.persistence.ExecutionJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pedrocmoreira.garage.execution.domain.saga.MessageTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionServiceTest {
    private ExecutionJobRepository repository;
    private InventoryService inventory;
    private MessagePublisher publisher;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExecutionJobRepository.class);
        inventory = mock(InventoryService.class);
        publisher = mock(MessagePublisher.class);
        service = new ExecutionService(repository, inventory, publisher, new SimpleMeterRegistry());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static ExecutionJob job(ExecutionStatus status) {
        return ExecutionJob.builder().id("j1").serviceOrderId(100L).serviceOrderNumber("OS-1").status(status)
                .parts(new java.util.ArrayList<>(List.of(new ExecutionJob.PartRequirement(2L, "Óleo", 4)))).build();
    }

    private void stored(ExecutionJob job) {
        when(repository.findByServiceOrderId(100L)).thenReturn(Optional.of(job));
    }

    private SagaMessage lastPublished() {
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        verify(publisher, atLeastOnce()).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void enqueueCreatesJobFromCommand() {
        when(repository.findByServiceOrderId(100L)).thenReturn(Optional.empty());
        SagaMessage cmd = SagaMessage.of(CMD_EXECUTION_START_DIAGNOSIS, 100L, "OS-1", Map.of(
                "vehiclePlate", "ABC1D23",
                "services", List.of(Map.of("name", "Troca de óleo")),
                "parts", List.of(Map.of("partId", 2, "name", "Óleo", "quantity", 4))));

        ExecutionJob job = service.enqueue(cmd);

        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.QUEUED_DIAGNOSIS);
        assertThat(job.getServices()).containsExactly("Troca de óleo");
        assertThat(job.getParts()).containsExactly(new ExecutionJob.PartRequirement(2L, "Óleo", 4));
        assertThat(job.getTimeline()).hasSize(1);
    }

    @Test
    void enqueueWithoutItemsAndIdempotent() {
        when(repository.findByServiceOrderId(100L)).thenReturn(Optional.empty());
        ExecutionJob job = service.enqueue(SagaMessage.of(CMD_EXECUTION_START_DIAGNOSIS, 100L, "OS-1", Map.of()));
        assertThat(job.getParts()).isEmpty();

        stored(job);
        assertThat(service.enqueue(SagaMessage.of(CMD_EXECUTION_START_DIAGNOSIS, 100L, "OS-1", Map.of()))).isSameAs(job);
        verify(repository, times(1)).save(any());
    }

    @Test
    void diagnosisFlowPublishesEvents() {
        ExecutionJob job = job(ExecutionStatus.QUEUED_DIAGNOSIS);
        stored(job);
        service.startDiagnosis(100L);
        assertThat(lastPublished().type()).isEqualTo(EVT_DIAGNOSIS_STARTED);
        service.completeDiagnosis(100L, "Óleo vencido");
        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.DIAGNOSED);
        assertThat(lastPublished().dataAsString("notes")).isEqualTo("Óleo vencido");
    }

    @Test
    void completeDiagnosisWithoutNotes() {
        stored(job(ExecutionStatus.IN_DIAGNOSIS));
        service.completeDiagnosis(100L, null);
        assertThat(lastPublished().data()).isEmpty();
    }

    @Test
    void invalidTransitionIsRejected() {
        stored(job(ExecutionStatus.QUEUED_DIAGNOSIS));
        assertThatThrownBy(() -> service.startRepair(100L)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void reservePartsSuccess() {
        ExecutionJob job = job(ExecutionStatus.DIAGNOSED);
        stored(job);
        when(inventory.reserveAll(job.getParts())).thenReturn(Optional.empty());

        service.reserveParts(SagaMessage.of(CMD_EXECUTION_RESERVE_PARTS, 100L, "OS-1", Map.of()));

        assertThat(job.isPartsReserved()).isTrue();
        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.AWAITING_REPAIR);
        assertThat(lastPublished().type()).isEqualTo(EVT_PARTS_RESERVED);
    }

    @Test
    void reservePartsFailurePublishesFailure() {
        ExecutionJob job = job(ExecutionStatus.DIAGNOSED);
        stored(job);
        when(inventory.reserveAll(any())).thenReturn(Optional.of("Estoque insuficiente"));

        service.reserveParts(SagaMessage.of(CMD_EXECUTION_RESERVE_PARTS, 100L, "OS-1", Map.of()));

        assertThat(job.isPartsReserved()).isFalse();
        SagaMessage event = lastPublished();
        assertThat(event.type()).isEqualTo(EVT_PARTS_RESERVATION_FAILED);
        assertThat(event.dataAsString("reason")).isEqualTo("Estoque insuficiente");
    }

    @Test
    void reservePartsIsIdempotent() {
        ExecutionJob job = job(ExecutionStatus.AWAITING_REPAIR);
        job.setPartsReserved(true);
        stored(job);
        service.reserveParts(SagaMessage.of(CMD_EXECUTION_RESERVE_PARTS, 100L, "OS-1", Map.of()));
        verifyNoInteractions(inventory);
        assertThat(lastPublished().type()).isEqualTo(EVT_PARTS_RESERVED);
    }

    @Test
    void repairFlowConsumesPartsAndCompletes() {
        ExecutionJob job = job(ExecutionStatus.AWAITING_REPAIR);
        stored(job);
        service.startRepair(100L);
        service.completeRepair(100L);
        verify(inventory).consumeAll(job.getParts());
        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(lastPublished().type()).isEqualTo(EVT_EXECUTION_COMPLETED);
    }

    @Test
    void cancelReleasesReservedParts() {
        ExecutionJob job = job(ExecutionStatus.AWAITING_REPAIR);
        job.setPartsReserved(true);
        stored(job);

        service.cancel(SagaMessage.of(CMD_EXECUTION_CANCEL, 100L, "OS-1", Map.of("reason", "pagamento recusado")));

        verify(inventory).releaseAll(job.getParts());
        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(job.getCancellationReason()).isEqualTo("pagamento recusado");
        assertThat(lastPublished().type()).isEqualTo(EVT_EXECUTION_CANCELLED);
    }

    @Test
    void cancelWithoutReservationAndFinalJobs() {
        ExecutionJob job = job(ExecutionStatus.QUEUED_DIAGNOSIS);
        stored(job);
        service.cancel(SagaMessage.of(CMD_EXECUTION_CANCEL, 100L, "OS-1", Map.of()));
        verifyNoInteractions(inventory);
        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        service.cancel(SagaMessage.of(CMD_EXECUTION_CANCEL, 100L, "OS-1", Map.of()));
        verify(publisher, times(1)).publish(any());

        when(repository.findByServiceOrderId(200L)).thenReturn(Optional.empty());
        service.cancel(SagaMessage.of(CMD_EXECUTION_CANCEL, 200L, "OS-2", Map.of()));
    }

    @Test
    void queriesAndPriority() {
        ExecutionJob job = job(ExecutionStatus.QUEUED_DIAGNOSIS);
        stored(job);
        when(repository.findAll()).thenReturn(List.of(job));
        when(repository.findByStatus(ExecutionStatus.QUEUED_DIAGNOSIS)).thenReturn(List.of(job));
        when(repository.findByStatusInOrderByPriorityDescCreatedAtAsc(any())).thenReturn(List.of(job));

        assertThat(service.list(null)).hasSize(1);
        assertThat(service.list(ExecutionStatus.QUEUED_DIAGNOSIS)).hasSize(1);
        assertThat(service.queue()).hasSize(1);
        assertThat(service.updatePriority(100L, 5).getPriority()).isEqualTo(5);
        when(repository.findByServiceOrderId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.find(1L)).isInstanceOf(EntityNotFoundException.class);
    }
}
