package com.pedrocmoreira.garage.execution.application;

import com.pedrocmoreira.garage.execution.domain.exception.EntityNotFoundException;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob.PartRequirement;
import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import com.pedrocmoreira.garage.execution.infrastructure.persistence.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reserva, liberação e baixa de peças. A reserva é "tudo ou nada" com rollback local. */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryRepository repository;

    /** @return motivo da falha, ou vazio se todas as peças foram reservadas. */
    public Optional<String> reserveAll(List<PartRequirement> parts) {
        for (PartRequirement part : parts) {
            Optional<InventoryItem> item = repository.findById(part.partId());
            if (item.isEmpty()) {
                return Optional.of("Peça " + part.name() + " (id " + part.partId() + ") não cadastrada no estoque");
            }
            if (!item.get().canReserve(part.quantity())) {
                return Optional.of(String.format("Estoque insuficiente para %s: disponível %d, solicitado %d",
                        part.name(), item.get().getAvailableQuantity(), part.quantity()));
            }
        }
        List<PartRequirement> reserved = new ArrayList<>();
        try {
            for (PartRequirement part : parts) {
                InventoryItem item = repository.findById(part.partId()).orElseThrow();
                item.reserve(part.quantity());
                repository.save(item);
                reserved.add(part);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Falha durante a reserva ({}), desfazendo {} reservas", e.getMessage(), reserved.size());
            releaseAll(reserved);
            return Optional.of("Falha ao reservar peças: " + e.getMessage());
        }
    }

    public void releaseAll(List<PartRequirement> parts) {
        parts.forEach(p -> repository.findById(p.partId()).ifPresent(item -> {
            item.release(p.quantity());
            repository.save(item);
        }));
    }

    public void consumeAll(List<PartRequirement> parts) {
        parts.forEach(p -> repository.findById(p.partId()).ifPresent(item -> {
            item.consume(p.quantity());
            repository.save(item);
        }));
    }

    public List<InventoryItem> list() {
        return repository.findAll();
    }

    public InventoryItem get(Long partId) {
        return repository.findById(partId).orElseThrow(() -> new EntityNotFoundException("Peça no estoque", partId));
    }

    public InventoryItem upsert(Long partId, String name, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("Quantidade disponível não pode ser negativa");
        }
        InventoryItem item = repository.findById(partId).orElseGet(() -> InventoryItem.builder().partId(partId).build());
        item.setName(name);
        item.setAvailableQuantity(availableQuantity);
        return repository.save(item);
    }

    public InventoryItem addStock(Long partId, int quantity) {
        InventoryItem item = get(partId);
        item.addStock(quantity);
        return repository.save(item);
    }
}
