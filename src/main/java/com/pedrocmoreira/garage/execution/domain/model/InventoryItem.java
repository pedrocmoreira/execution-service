package com.pedrocmoreira.garage.execution.domain.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Estoque de peças do almoxarifado (MongoDB). O {@code partId} é o mesmo do catálogo do OS Service.
 * {@link Version} garante bloqueio otimista contra reservas concorrentes.
 */
@Document("parts_inventory")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryItem {
    @Id
    private Long partId;

    private String name;

    @Builder.Default
    private int availableQuantity = 0;

    @Builder.Default
    private int reservedQuantity = 0;

    @Version
    private Long version;

    private Instant updatedAt;

    public boolean canReserve(int quantity) {
        return availableQuantity >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Estoque insuficiente para " + name);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        updatedAt = Instant.now();
    }

    /** Compensação: devolve a quantidade reservada ao estoque disponível. */
    public void release(int quantity) {
        int amount = Math.min(quantity, reservedQuantity);
        reservedQuantity -= amount;
        availableQuantity += amount;
        updatedAt = Instant.now();
    }

    /** Baixa definitiva das peças utilizadas no reparo. */
    public void consume(int quantity) {
        reservedQuantity -= Math.min(quantity, reservedQuantity);
        updatedAt = Instant.now();
    }

    public void addStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva");
        }
        availableQuantity += quantity;
        updatedAt = Instant.now();
    }
}
