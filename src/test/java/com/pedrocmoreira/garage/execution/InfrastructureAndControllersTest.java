package com.pedrocmoreira.garage.execution;

import com.pedrocmoreira.garage.execution.application.ExecutionCommandHandler;
import com.pedrocmoreira.garage.execution.application.ExecutionService;
import com.pedrocmoreira.garage.execution.application.InventoryService;
import com.pedrocmoreira.garage.execution.domain.exception.InvalidStatusTransitionException;
import com.pedrocmoreira.garage.execution.domain.model.ExecutionStatus;
import com.pedrocmoreira.garage.execution.domain.model.InventoryItem;
import com.pedrocmoreira.garage.execution.domain.saga.MessageTypes;
import com.pedrocmoreira.garage.execution.domain.saga.SagaMessage;
import com.pedrocmoreira.garage.execution.infrastructure.messaging.ExecutionCommandListener;
import com.pedrocmoreira.garage.execution.infrastructure.messaging.RabbitMessagePublisher;
import com.pedrocmoreira.garage.execution.infrastructure.security.JwtValidationFilter;
import com.pedrocmoreira.garage.execution.presentation.controller.ExecutionController;
import com.pedrocmoreira.garage.execution.presentation.controller.GlobalExceptionHandler;
import com.pedrocmoreira.garage.execution.presentation.controller.InventoryController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InfrastructureAndControllersTest {
    private static final String SECRET = "segredo-de-teste-com-mais-de-32-caracteres!!";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandHandlerRoutesCommands() {
        ExecutionService service = mock(ExecutionService.class);
        ExecutionCommandHandler handler = new ExecutionCommandHandler(service);
        SagaMessage start = SagaMessage.of(MessageTypes.CMD_EXECUTION_START_DIAGNOSIS, 1L, "OS", Map.of());
        SagaMessage reserve = SagaMessage.of(MessageTypes.CMD_EXECUTION_RESERVE_PARTS, 1L, "OS", Map.of());
        SagaMessage cancel = SagaMessage.of(MessageTypes.CMD_EXECUTION_CANCEL, 1L, "OS", Map.of());
        new ExecutionCommandListener(handler).onCommand(start);
        handler.handle(reserve);
        handler.handle(cancel);
        handler.handle(SagaMessage.of("command.execution.other", 1L, "OS", Map.of()));
        verify(service).enqueue(start);
        verify(service).reserveParts(reserve);
        verify(service).cancel(cancel);
    }

    @Test
    void publisherSendsToExchange() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        SagaMessage msg = SagaMessage.of(MessageTypes.EVT_PARTS_RESERVED, 1L, "OS", Map.of());
        new RabbitMessagePublisher(template).publish(msg);
        verify(template).convertAndSend(eq(MessageTypes.EXCHANGE), eq(msg.type()), eq(msg), any(MessagePostProcessor.class));
    }

    @Test
    void jwtFilter() throws Exception {
        JwtValidationFilter filter = new JwtValidationFilter(SECRET);
        String token = Jwts.builder().subject("mecanico").claim("role", "ROLE_MECANICO")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("mecanico");

        SecurityContextHolder.clearContext();
        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "Bearer x");
        filter.doFilter(bad, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void controllers() throws Exception {
        ExecutionService service = mock(ExecutionService.class);
        InventoryService inventory = mock(InventoryService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExecutionController(service), new InventoryController(inventory))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var job = ExecutionServiceTest.job(ExecutionStatus.QUEUED_DIAGNOSIS);
        when(service.list(any())).thenReturn(List.of(job));
        when(service.queue()).thenReturn(List.of(job));
        when(service.find(100L)).thenReturn(job);
        when(service.startDiagnosis(100L)).thenReturn(job);
        when(service.completeDiagnosis(eq(100L), any())).thenReturn(job);
        when(service.startRepair(100L)).thenReturn(job);
        when(service.completeRepair(100L)).thenReturn(job);
        when(service.updatePriority(100L, 3)).thenReturn(job);
        InventoryItem item = InventoryItem.builder().partId(1L).name("Filtro").availableQuantity(5).build();
        when(inventory.list()).thenReturn(List.of(item));
        when(inventory.get(1L)).thenReturn(item);
        when(inventory.upsert(1L, "Filtro", 5)).thenReturn(item);
        when(inventory.addStock(1L, 2)).thenReturn(item);

        mvc.perform(get("/api/executions?status=QUEUED_DIAGNOSIS")).andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceOrderNumber").value("OS-1"));
        mvc.perform(get("/api/executions/queue")).andExpect(status().isOk());
        mvc.perform(get("/api/executions/100")).andExpect(status().isOk());
        mvc.perform(post("/api/executions/100/diagnosis/start")).andExpect(status().isOk());
        mvc.perform(post("/api/executions/100/diagnosis/complete").contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"ok\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/executions/100/diagnosis/complete")).andExpect(status().isOk());
        mvc.perform(post("/api/executions/100/repair/start")).andExpect(status().isOk());
        mvc.perform(post("/api/executions/100/repair/complete")).andExpect(status().isOk());
        mvc.perform(patch("/api/executions/100/priority?value=3")).andExpect(status().isOk());

        mvc.perform(get("/api/inventory")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Filtro"));
        mvc.perform(get("/api/inventory/1")).andExpect(status().isOk());
        mvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Filtro\",\"availableQuantity\":5}")).andExpect(status().isOk());
        mvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/inventory/1/add?quantity=2")).andExpect(status().isOk());

        when(service.startRepair(100L)).thenThrow(new InvalidStatusTransitionException("inválida"));
        mvc.perform(post("/api/executions/100/repair/start")).andExpect(status().is(422));
        when(inventory.addStock(1L, 0)).thenThrow(new IllegalArgumentException("x"));
        mvc.perform(patch("/api/inventory/1/add?quantity=0")).andExpect(status().isBadRequest());
        when(service.queue()).thenThrow(new IllegalStateException("x"));
        mvc.perform(get("/api/executions/queue")).andExpect(status().isInternalServerError());
    }
}
