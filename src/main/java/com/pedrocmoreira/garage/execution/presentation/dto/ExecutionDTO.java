package com.pedrocmoreira.garage.execution.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class ExecutionDTO {
    private ExecutionDTO() {}

    public record DiagnosisRequest(String notes) {}

    public record InventoryRequest(@NotBlank String name, @NotNull @PositiveOrZero Integer availableQuantity) {}
}
