package com.ecommerce.order.service.impl;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderEventProducer;
import com.ecommerce.order.exception.InvalidOrderStateException;
import com.ecommerce.order.exception.InventoryUnavailableException;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.exception.PaymentDeclinedException;
import com.ecommerce.order.integration.InventoryClient;
import com.ecommerce.order.integration.PaymentClient;
import com.ecommerce.order.integration.ProductClient;
import com.ecommerce.order.integration.dto.AuthorizePaymentRequest;
import com.ecommerce.order.integration.dto.PaymentAuthorizationResponse;
import com.ecommerce.order.integration.dto.ReserveStockRequest;
import com.ecommerce.order.integration.dto.StockAvailabilityResponse;
import com.ecommerce.order.mapper.OrderMapper;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OrderSpecifications;
import com.ecommerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING, EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.FAILED),
            OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.FAILED),
            OrderStatus.PAID, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class));

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final ProductClient productClient;
    private final OrderEventProducer orderEventProducer;

    @Override
    @Transactional
    public IdempotentResult<OrderResponse> createOrder(OrderRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return IdempotentResult.replayed(orderMapper.toResponse(existing.get()));
            }
        }

        var items = request.items().stream()
                .map(this::reserveAndPrice)
                .toList();

        BigDecimal total = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.create(request.customerId(), items, total, idempotencyKey);
        // order.getId() is null until persisted (client-generated UUID strategy assigns it on
        // save/flush, not on construction) — save first so payment-service's @NotNull orderId
        // validation has a real id to authorize against. saveAndFlush (not save) so a duplicate
        // idempotency-key race hits the unique-index violation here, inside this try, rather
        // than silently at end-of-transaction flush where we're no longer prepared to catch it.
        Order saved;
        try {
            saved = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            // Lost a race to a concurrent request carrying the same key — the reservations this
            // attempt already made above are an accepted, narrow leak for that rare case (see
            // docs/architecture.html); returning the winner's order is what the client wants.
            Order winner = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return IdempotentResult.replayed(orderMapper.toResponse(winner));
        }

        PaymentAuthorizationResponse payment = paymentClient.authorize(
                new AuthorizePaymentRequest(saved.getId(), saved.getCustomerId(), total));
        if (!payment.authorized()) {
            throw new PaymentDeclinedException(payment.declineReason());
        }

        saved.setStatus(OrderStatus.CONFIRMED);
        saved = orderRepository.save(saved);
        orderEventProducer.publishCreated(saved);
        return IdempotentResult.created(orderMapper.toResponse(saved));
    }

    private OrderItem reserveAndPrice(OrderItemRequest itemRequest) {
        StockAvailabilityResponse availability = inventoryClient.checkAvailability(
                itemRequest.productId(), itemRequest.quantity());
        if (!availability.available()) {
            throw new InventoryUnavailableException(itemRequest.productId(), itemRequest.quantity(),
                    availability.quantityOnHand());
        }
        inventoryClient.reserve(itemRequest.productId(), new ReserveStockRequest(itemRequest.quantity()));
        BigDecimal unitPrice = productClient.getProduct(itemRequest.productId()).price();
        return OrderItem.of(itemRequest.productId(), itemRequest.quantity(), unitPrice);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        return orderMapper.toResponse(findOrderOrThrow(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listOrders(UUID customerId, OrderStatus status, Instant createdAfter,
                                                   Pageable pageable) {
        Specification<Order> spec = Specification
                .where(OrderSpecifications.hasCustomerId(customerId))
                .and(OrderSpecifications.hasStatus(status))
                .and(OrderSpecifications.createdAfter(createdAfter));

        Page<OrderResponse> page = orderRepository.findAll(spec, pageable).map(orderMapper::toResponse);
        return PageResponse.from(page);
    }

    @Override
    @Transactional
    public OrderResponse replaceOrder(UUID orderId, OrderRequest request) {
        Order order = findOrderOrThrow(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(order.getStatus(), OrderStatus.PENDING);
        }
        order.getItems().clear();
        request.items().forEach(i -> order.addItem(OrderItem.of(i.productId(), i.quantity(), BigDecimal.ZERO)));
        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderResponse updateStatus(UUID orderId, OrderStatus newStatus) {
        Order order = findOrderOrThrow(orderId);
        Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidOrderStateException(order.getStatus(), newStatus);
        }
        order.setStatus(newStatus);
        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public void cancelOrder(UUID orderId, String reason) {
        Order order = findOrderOrThrow(orderId);
        Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(OrderStatus.CANCELLED)) {
            throw new InvalidOrderStateException(order.getStatus(), OrderStatus.CANCELLED);
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        orderEventProducer.publishCancelled(order, reason);
    }

    private Order findOrderOrThrow(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
