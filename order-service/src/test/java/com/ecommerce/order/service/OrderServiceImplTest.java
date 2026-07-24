package com.ecommerce.order.service;

import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderEventProducer;
import com.ecommerce.order.exception.InventoryUnavailableException;
import com.ecommerce.order.exception.PaymentDeclinedException;
import com.ecommerce.order.integration.InventoryClient;
import com.ecommerce.order.integration.PaymentClient;
import com.ecommerce.order.integration.ProductClient;
import com.ecommerce.order.integration.dto.PaymentAuthorizationResponse;
import com.ecommerce.order.integration.dto.ProductResponse;
import com.ecommerce.order.integration.dto.StockAvailabilityResponse;
import com.ecommerce.order.mapper.OrderMapper;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderMapper orderMapper;
    @Mock private InventoryClient inventoryClient;
    @Mock private PaymentClient paymentClient;
    @Mock private ProductClient productClient;
    @Mock private OrderEventProducer orderEventProducer;

    @InjectMocks
    private OrderServiceImpl orderService;

    private UUID customerId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    private void stubPricingAndStock(int quantity, int quantityOnHand) {
        when(inventoryClient.checkAvailability(productId, quantity))
                .thenReturn(new StockAvailabilityResponse(productId, true, quantityOnHand));
        when(productClient.getProduct(productId))
                .thenReturn(new ProductResponse(productId, "SKU-1", "Test Product", new BigDecimal("19.99")));
    }

    @Test
    void createOrder_confirmsOrder_whenStockAvailableAndPaymentAuthorized() {
        var request = new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 2)));
        stubPricingAndStock(2, 10);
        when(paymentClient.authorize(any())).thenReturn(new PaymentAuthorizationResponse(UUID.randomUUID(), true, null));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderMapper.toResponse(any(Order.class))).thenReturn(
                new OrderResponse(UUID.randomUUID(), customerId, OrderStatus.CONFIRMED, BigDecimal.ZERO, List.of(), null, null));

        var result = orderService.createOrder(request, null);

        assertThat(result.created()).isTrue();
        assertThat(result.body().status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient).reserve(eq(productId), any());
        verify(orderEventProducer).publishCreated(any(Order.class));
    }

    @Test
    void createOrder_throws_whenStockUnavailable() {
        var request = new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 5)));
        when(inventoryClient.checkAvailability(productId, 5))
                .thenReturn(new StockAvailabilityResponse(productId, false, 1));

        assertThatThrownBy(() -> orderService.createOrder(request, null))
                .isInstanceOf(InventoryUnavailableException.class);

        verifyNoInteractions(paymentClient, orderEventProducer);
    }

    @Test
    void createOrder_throws_whenPaymentDeclined() {
        var request = new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 1)));
        stubPricingAndStock(1, 10);
        when(paymentClient.authorize(any()))
                .thenReturn(new PaymentAuthorizationResponse(null, false, "insufficient funds"));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> orderService.createOrder(request, null))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("insufficient funds");

        // The pre-payment persist (saveAndFlush, to obtain an id) already happened; it's the
        // post-payment status update (plain save) that must never run once payment is declined.
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderEventProducer);
    }

    @Test
    void createOrder_returnsExistingOrder_whenIdempotencyKeyAlreadySeen() {
        var request = new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 2)));
        Order existing = Order.create(customerId, List.of(), BigDecimal.TEN, "key-123");
        when(orderRepository.findByIdempotencyKey("key-123")).thenReturn(Optional.of(existing));
        when(orderMapper.toResponse(existing)).thenReturn(
                new OrderResponse(UUID.randomUUID(), customerId, OrderStatus.CONFIRMED, BigDecimal.TEN, List.of(), null, null));

        var result = orderService.createOrder(request, "key-123");

        assertThat(result.created()).isFalse();
        verifyNoInteractions(inventoryClient, paymentClient, productClient, orderEventProducer);
    }

    @Test
    void createOrder_returnsWinnersOrder_whenConcurrentRequestRacesOnSameIdempotencyKey() {
        var request = new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 1)));
        stubPricingAndStock(1, 10);
        when(orderRepository.findByIdempotencyKey("key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Order.create(customerId, List.of(), BigDecimal.TEN, "key-race")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(orderMapper.toResponse(any(Order.class))).thenReturn(
                new OrderResponse(UUID.randomUUID(), customerId, OrderStatus.CONFIRMED, BigDecimal.TEN, List.of(), null, null));

        var result = orderService.createOrder(request, "key-race");

        assertThat(result.created()).isFalse();
        verifyNoInteractions(paymentClient, orderEventProducer);
    }

    @Test
    void getOrder_throws_whenNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(orderId))
                .isInstanceOf(com.ecommerce.order.exception.OrderNotFoundException.class);
    }
}
