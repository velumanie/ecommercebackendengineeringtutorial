package com.ecommerce.order.exception;

import com.ecommerce.order.entity.OrderStatus;

public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(OrderStatus current, OrderStatus requested) {
        super("Cannot transition order from %s to %s".formatted(current, requested));
    }
}
