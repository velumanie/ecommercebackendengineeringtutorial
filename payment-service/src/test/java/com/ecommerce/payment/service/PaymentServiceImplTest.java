package com.ecommerce.payment.service;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.payment.dto.AuthorizePaymentRequest;
import com.ecommerce.payment.dto.PaymentAuthorizationResponse;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.entity.PaymentStatus;
import com.ecommerce.payment.event.PaymentEventProducer;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.mapper.PaymentMapper;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentMapper paymentMapper;
    @Mock private PaymentEventProducer paymentEventProducer;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Test
    void authorize_approvesAndPublishesCompleted_whenAmountPositive() {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotentResult<PaymentAuthorizationResponse> result = paymentService.authorize(request, null);

        assertThat(result.created()).isTrue();
        assertThat(result.body().authorized()).isTrue();
        assertThat(result.body().declineReason()).isNull();
        verify(paymentEventProducer).publishCompleted(any(Payment.class));
        verify(paymentEventProducer, org.mockito.Mockito.never()).publishFailed(any(), any());
    }

    @Test
    void authorize_declinesAndPublishesFailed_whenAmountNotPositive() {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotentResult<PaymentAuthorizationResponse> result = paymentService.authorize(request, null);

        assertThat(result.created()).isTrue();
        assertThat(result.body().authorized()).isFalse();
        assertThat(result.body().declineReason()).isEqualTo("amount must be positive");
        verify(paymentEventProducer).publishFailed(any(Payment.class), any());
        verify(paymentEventProducer, org.mockito.Mockito.never()).publishCompleted(any());
    }

    @Test
    void authorize_returnsExistingPayment_whenIdempotencyKeyAlreadySeen() {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"));
        Payment existing = Payment.create(request.orderId(), request.customerId(), request.amount(), "key-1");
        existing.setStatus(PaymentStatus.AUTHORIZED);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        IdempotentResult<PaymentAuthorizationResponse> result = paymentService.authorize(request, "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.body().authorized()).isTrue();
        verify(paymentRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        verify(paymentEventProducer, org.mockito.Mockito.never()).publishCompleted(any());
    }

    @Test
    void authorize_returnsWinnersPayment_whenConcurrentRequestRacesOnSameKey() {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"));
        Payment winner = Payment.create(request.orderId(), request.customerId(), request.amount(), "key-2");
        winner.setStatus(PaymentStatus.AUTHORIZED);
        when(paymentRepository.findByIdempotencyKey("key-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        IdempotentResult<PaymentAuthorizationResponse> result = paymentService.authorize(request, "key-2");

        assertThat(result.created()).isFalse();
        assertThat(result.body().authorized()).isTrue();
        verify(paymentEventProducer, org.mockito.Mockito.never()).publishCompleted(any());
    }

    @Test
    void get_throws_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.get(id)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void get_returnsMappedResponse_whenFound() {
        UUID id = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), null);
        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));
        when(paymentMapper.toResponse(payment)).thenReturn(
                new PaymentResponse(id, payment.getOrderId(), payment.getCustomerId(), PaymentStatus.AUTHORIZED,
                        payment.getAmount(), "CARD", null));

        PaymentResponse response = paymentService.get(id);

        assertThat(response.id()).isEqualTo(id);
    }

    @Test
    void refund_setsStatusRefunded_andAddsTransaction() {
        UUID id = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), null);
        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        paymentService.refund(id);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getTransactions()).hasSize(1);
        verify(paymentRepository).save(payment);
    }
}
