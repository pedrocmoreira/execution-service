package com.pedrocmoreira.garage.execution.presentation.controller;

import com.pedrocmoreira.garage.execution.application.InventoryService;
import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import com.pedrocmoreira.garage.execution.presentation.dto.ExecutionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Estoque de peças")
public class InventoryController {
    private final InventoryService service;

    @GetMapping
    @Operation(summary = "Listar estoque")
    public List<InventoryItem> list() {
        return service.list();
    }

    @GetMapping("/{partId}")
    @Operation(summary = "Consultar estoque de uma peça")
    public InventoryItem get(@PathVariable Long partId) {
        return service.get(partId);
    }

    @PutMapping("/{partId}")
    @Operation(summary = "Cadastrar/ajustar estoque de uma peça (partId = id do catálogo do OS Service)")
    public InventoryItem upsert(@PathVariable Long partId, @Valid @RequestBody ExecutionDTO.InventoryRequest request) {
        return service.upsert(partId, request.name(), request.availableQuantity());
    }

    @PatchMapping("/{partId}/add")
    @Operation(summary = "Repor estoque")
    public InventoryItem add(@PathVariable Long partId, @RequestParam int quantity) {
        return service.addStock(partId, quantity);
    }
}
