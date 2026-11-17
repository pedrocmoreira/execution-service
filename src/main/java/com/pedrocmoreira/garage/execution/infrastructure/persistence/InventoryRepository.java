package com.pedrocmoreira.garage.execution.infrastructure.persistence;

import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InventoryRepository extends MongoRepository<InventoryItem, Long> {
}
