package com.ecommerce.order.integration;

import com.ecommerce.order.integration.dto.AuthorizePaymentRequest;
import com.ecommerce.order.integration.dto.PaymentAuthorizationResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "payment-service",
        configuration = com.ecommerce.order.config.FeignClientConfig.class,
        fallbackFactory = PaymentClientFallbackFactory.class)
public interface PaymentClient {

    @PostMapping("/api/v1/payments/authorize")
    PaymentAuthorizationResponse authorize(@RequestBody AuthorizePaymentRequest request);
}
