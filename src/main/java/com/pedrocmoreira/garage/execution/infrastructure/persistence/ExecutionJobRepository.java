package com.pedrocmoreira.garage.execution.infrastructure.persistence;

import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository extends MongoRepository<ExecutionJob, String> {
    Optional<ExecutionJob> findByServiceOrderId(Long serviceOrderId);
    List<ExecutionJob> findByStatus(ExecutionStatus status);
    List<ExecutionJob> findByStatusInOrderByPriorityDescCreatedAtAsc(Collection<ExecutionStatus> statuses);
}
