package com.pedrocmoreira.garage.execution.infrastructure.config;

import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import com.pedrocmoreira.garage.execution.infrastructure.persistence.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Estoque inicial de demonstração (mesmos IDs do catálogo do OS Service).
 * A pastilha de freio (id 3) começa zerada para demonstrar a compensação por falta de peças.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.inventory", havingValue = "true", matchIfMissing = true)
public class InventorySeeder implements ApplicationRunner {
    private final InventoryRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(List.of(
                InventoryItem.builder().partId(1L).name("Filtro de óleo").availableQuantity(20).build(),
                InventoryItem.builder().partId(2L).name("Óleo 5W30 (litro)").availableQuantity(40).build(),
                InventoryItem.builder().partId(3L).name("Pastilha de freio dianteira").availableQuantity(0).build()));
        log.info("Estoque de demonstração criado");
    }
}
