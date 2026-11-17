package com.pedrocmoreira.garage.execution;

import com.pedrocmoreira.garage.execution.application.InventoryService;
import com.pedrocmoreira.garage.execution.domain.exception.EntityNotFoundException;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob.PartRequirement;
import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import com.pedrocmoreira.garage.execution.infrastructure.persistence.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryServiceTest {
    private InventoryRepository repository;
    private InventoryService service;
    private final Map<Long, InventoryItem> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(InventoryRepository.class);
        service = new InventoryService(repository);
        store.put(1L, InventoryItem.builder().partId(1L).name("Filtro").availableQuantity(10).build());
        store.put(2L, InventoryItem.builder().partId(2L).name("Óleo").availableQuantity(4).build());
        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            InventoryItem item = inv.getArgument(0);
            store.put(item.getPartId(), item);
            return item;
        });
    }

    @Test
    void reserveAllOrNothing() {
        List<PartRequirement> parts = List.of(new PartRequirement(1L, "Filtro", 2), new PartRequirement(2L, "Óleo", 4));
        assertThat(service.reserveAll(parts)).isEmpty();
        assertThat(store.get(1L).getAvailableQuantity()).isEqualTo(8);
        assertThat(store.get(1L).getReservedQuantity()).isEqualTo(2);
        assertThat(store.get(2L).getAvailableQuantity()).isZero();

        Optional<String> failure = service.reserveAll(List.of(new PartRequirement(1L, "Filtro", 1), new PartRequirement(2L, "Óleo", 1)));
        assertThat(failure).get().asString().contains("Estoque insuficiente para Óleo");
        assertThat(store.get(1L).getAvailableQuantity()).isEqualTo(8);

        assertThat(service.reserveAll(List.of(new PartRequirement(9L, "X", 1)))).get().asString().contains("não cadastrada");
    }

    @Test
    void reserveRollsBackOnUnexpectedError() {
        doAnswer(inv -> {
            InventoryItem item = inv.getArgument(0);
            if (item.getPartId() == 2L && item.getReservedQuantity() > 0) throw new IllegalStateException("conflito de versão");
            store.put(item.getPartId(), item);
            return item;
        }).when(repository).save(any());
        Optional<String> failure = service.reserveAll(List.of(new PartRequirement(1L, "Filtro", 2), new PartRequirement(2L, "Óleo", 1)));
        assertThat(failure).get().asString().contains("conflito de versão");
        assertThat(store.get(1L).getAvailableQuantity()).isEqualTo(10);
        assertThat(store.get(1L).getReservedQuantity()).isZero();
    }

    @Test
    void releaseAndConsume() {
        List<PartRequirement> parts = List.of(new PartRequirement(1L, "Filtro", 3), new PartRequirement(9L, "X", 1));
        store.get(1L).reserve(3);
        service.releaseAll(parts);
        assertThat(store.get(1L).getAvailableQuantity()).isEqualTo(10);

        store.get(1L).reserve(3);
        service.consumeAll(parts);
        assertThat(store.get(1L).getAvailableQuantity()).isEqualTo(7);
        assertThat(store.get(1L).getReservedQuantity()).isZero();
    }

    @Test
    void stockManagement() {
        when(repository.findAll()).thenReturn(List.copyOf(store.values()));
        assertThat(service.list()).hasSize(2);
        assertThat(service.get(1L).getName()).isEqualTo("Filtro");
        assertThatThrownBy(() -> service.get(9L)).isInstanceOf(EntityNotFoundException.class);

        assertThat(service.upsert(3L, "Pastilha", 5).getAvailableQuantity()).isEqualTo(5);
        assertThat(service.upsert(1L, "Filtro novo", 1).getName()).isEqualTo("Filtro novo");
        assertThatThrownBy(() -> service.upsert(1L, "x", -1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(service.addStock(1L, 4).getAvailableQuantity()).isEqualTo(5);
        assertThatThrownBy(() -> service.addStock(1L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inventoryItemRules() {
        InventoryItem item = InventoryItem.builder().partId(1L).name("A").availableQuantity(1).build();
        assertThatThrownBy(() -> item.reserve(2)).isInstanceOf(IllegalStateException.class);
        item.release(5);
        assertThat(item.getAvailableQuantity()).isEqualTo(1);
    }
}
