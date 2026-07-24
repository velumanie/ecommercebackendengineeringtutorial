package com.ecommerce.order.service;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.dto.PageResponse;
import com.ecommerce.order.entity.OrderStatus;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface OrderService {

    IdempotentResult<OrderResponse> createOrder(OrderRequest request, String idempotencyKey);

    OrderResponse getOrder(UUID orderId);

    PageResponse<OrderResponse> listOrders(UUID customerId, OrderStatus status, Instant createdAfter, Pageable pageable);

    OrderResponse replaceOrder(UUID orderId, OrderRequest request);

    OrderResponse updateStatus(UUID orderId, OrderStatus newStatus);

    void cancelOrder(UUID orderId, String reason);
}
