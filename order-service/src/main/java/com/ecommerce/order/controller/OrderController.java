package com.ecommerce.order.controller;

import com.ecommerce.order.dto.*;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Instant createdAfter,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return orderService.listOrders(customerId, status, createdAfter, pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = orderService.createOrder(request, idempotencyKey);
        OrderResponse body = result.body();
        // 201 for a genuinely new order; 200 for a replayed Idempotency-Key so the client can
        // tell "just created" from "you already did this" without inspecting the body.
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/orders/" + body.id())).body(body)
                : ResponseEntity.ok(body);
    }

    @PutMapping("/{id}")
    public OrderResponse replace(@PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return orderService.replaceOrder(id, request);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.updateStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, @RequestParam(required = false, defaultValue = "customer request") String reason) {
        orderService.cancelOrder(id, reason);
    }
}
