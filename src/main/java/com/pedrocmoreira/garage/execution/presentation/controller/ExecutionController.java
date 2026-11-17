package com.pedrocmoreira.garage.execution.presentation.controller;

import com.pedrocmoreira.garage.execution.application.ExecutionService;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionJob;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionStatus;
import com.pedrocmoreira.garage.execution.presentation.dto.ExecutionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Tag(name = "Execução e Produção", description = "Fila da oficina, diagnóstico e reparo (mecânico)")
public class ExecutionController {
    private final ExecutionService service;

    @GetMapping
    @Operation(summary = "Listar execuções (opcionalmente por status)")
    public List<ExecutionJob> list(@RequestParam(required = false) ExecutionStatus status) {
        return service.list(status);
    }

    @GetMapping("/queue")
    @Operation(summary = "Fila de trabalho ativa (prioridade desc, chegada asc)")
    public List<ExecutionJob> queue() {
        return service.queue();
    }

    @GetMapping("/{serviceOrderId}")
    @Operation(summary = "Execução de uma OS")
    public ExecutionJob get(@PathVariable Long serviceOrderId) {
        return service.find(serviceOrderId);
    }

    @PostMapping("/{serviceOrderId}/diagnosis/start")
    @Operation(summary = "Mecânico inicia o diagnóstico")
    public ExecutionJob startDiagnosis(@PathVariable Long serviceOrderId) {
        return service.startDiagnosis(serviceOrderId);
    }

    @PostMapping("/{serviceOrderId}/diagnosis/complete")
    @Operation(summary = "Mecânico conclui o diagnóstico (gera o orçamento no Billing)")
    public ExecutionJob completeDiagnosis(@PathVariable Long serviceOrderId,
                                          @RequestBody(required = false) ExecutionDTO.DiagnosisRequest request) {
        return service.completeDiagnosis(serviceOrderId, request == null ? null : request.notes());
    }

    @PostMapping("/{serviceOrderId}/repair/start")
    @Operation(summary = "Mecânico inicia o reparo")
    public ExecutionJob startRepair(@PathVariable Long serviceOrderId) {
        return service.startRepair(serviceOrderId);
    }

    @PostMapping("/{serviceOrderId}/repair/complete")
    @Operation(summary = "Mecânico conclui o reparo (comunica a finalização ao OS Service)")
    public ExecutionJob completeRepair(@PathVariable Long serviceOrderId) {
        return service.completeRepair(serviceOrderId);
    }

    @PatchMapping("/{serviceOrderId}/priority")
    @Operation(summary = "Alterar prioridade na fila")
    public ExecutionJob priority(@PathVariable Long serviceOrderId, @RequestParam int value) {
        return service.updatePriority(serviceOrderId, value);
    }
}
