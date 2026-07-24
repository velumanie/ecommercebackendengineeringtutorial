package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.AuthorizePaymentRequest;
import com.ecommerce.payment.dto.PaymentAuthorizationResponse;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/authorize")
    public PaymentAuthorizationResponse authorize(
            @Valid @RequestBody AuthorizePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.authorize(request, idempotencyKey).body();
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.get(id);
    }

    @PostMapping("/{id}/refund")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refund(@PathVariable UUID id) {
        paymentService.refund(id);
    }
}
