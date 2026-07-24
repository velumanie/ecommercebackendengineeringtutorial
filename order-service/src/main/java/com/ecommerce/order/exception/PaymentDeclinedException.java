package com.ecommerce.order.exception;

public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String reason) {
        super("Payment declined: " + reason);
    }

    public PaymentDeclinedException(String reason, Throwable cause) {
        super("Payment declined: " + reason, cause);
    }
}
