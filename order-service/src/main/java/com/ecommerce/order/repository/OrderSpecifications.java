package com.ecommerce.order.repository;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> hasCustomerId(UUID customerId) {
        return (root, query, cb) -> customerId == null ? null : cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Order> createdAfter(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }
}
