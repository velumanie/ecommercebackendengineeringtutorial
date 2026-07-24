package com.ecommerce.payment.service.impl;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.payment.dto.AuthorizePaymentRequest;
import com.ecommerce.payment.dto.PaymentAuthorizationResponse;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.entity.PaymentStatus;
import com.ecommerce.payment.entity.PaymentTransaction;
import com.ecommerce.payment.event.PaymentEventProducer;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.mapper.PaymentMapper;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentEventProducer paymentEventProducer;

    @Override
    @Transactional
    public IdempotentResult<PaymentAuthorizationResponse> authorize(AuthorizePaymentRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return IdempotentResult.replayed(toAuthorizationResponse(existing.get()));
            }
        }

        Payment payment = Payment.create(request.orderId(), request.customerId(), request.amount(), idempotencyKey);

        // In production this calls out to the actual payment gateway (Stripe/Adyen/etc.);
        // the reference implementation authorizes deterministically so the order flow is testable end to end.
        boolean approved = request.amount().signum() > 0;
        payment.setStatus(approved ? PaymentStatus.AUTHORIZED : PaymentStatus.FAILED);
        if (approved) {
            payment.addTransaction(PaymentTransaction.of("AUTH", "sim-" + UUID.randomUUID()));
        }

        Payment saved;
        try {
            // saveAndFlush (not save) so a unique-constraint violation on idempotency_key or the
            // pre-existing order_id constraint surfaces here, synchronously, instead of at
            // end-of-transaction flush where this catch block is no longer in scope.
            saved = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            Payment winner = (idempotencyKey != null
                    ? paymentRepository.findByIdempotencyKey(idempotencyKey)
                    : Optional.<Payment>empty())
                    .or(() -> paymentRepository.findByOrderId(request.orderId()))
                    .orElseThrow(() -> e);
            return IdempotentResult.replayed(toAuthorizationResponse(winner));
        }

        if (approved) {
            paymentEventProducer.publishCompleted(saved);
        } else {
            paymentEventProducer.publishFailed(saved, "amount must be positive");
        }
        return IdempotentResult.created(toAuthorizationResponse(saved));
    }

    private PaymentAuthorizationResponse toAuthorizationResponse(Payment payment) {
        boolean authorized = payment.getStatus() == PaymentStatus.AUTHORIZED;
        return new PaymentAuthorizationResponse(payment.getId(), authorized, authorized ? null : "amount must be positive");
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        return paymentMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional
    public void refund(UUID id) {
        Payment payment = findOrThrow(id);
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.addTransaction(PaymentTransaction.of("REFUND", "sim-" + UUID.randomUUID()));
        paymentRepository.save(payment);
    }

    private Payment findOrThrow(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
