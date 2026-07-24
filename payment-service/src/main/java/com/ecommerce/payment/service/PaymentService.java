package com.ecommerce.payment.service;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.payment.dto.AuthorizePaymentRequest;
import com.ecommerce.payment.dto.PaymentAuthorizationResponse;
import com.ecommerce.payment.dto.PaymentResponse;

import java.util.UUID;

public interface PaymentService {
    IdempotentResult<PaymentAuthorizationResponse> authorize(AuthorizePaymentRequest request, String idempotencyKey);
    PaymentResponse get(UUID id);
    void refund(UUID id);
}
