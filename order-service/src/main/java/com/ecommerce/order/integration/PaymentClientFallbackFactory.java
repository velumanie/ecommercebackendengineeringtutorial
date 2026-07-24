package com.ecommerce.order.integration;

import com.ecommerce.order.exception.PaymentDeclinedException;
import com.ecommerce.order.integration.dto.AuthorizePaymentRequest;
import com.ecommerce.order.integration.dto.PaymentAuthorizationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClientFallbackFactory implements FallbackFactory<PaymentClient> {

    private static final Logger log = LoggerFactory.getLogger(PaymentClientFallbackFactory.class);

    @Override
    public PaymentClient create(Throwable cause) {
        return request -> {
            log.warn("payment-service unavailable while authorizing order {}", request.orderId(), cause);
            throw new PaymentDeclinedException("payment service unavailable", cause);
        };
    }
}
