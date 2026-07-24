package com.ecommerce.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "gateway_ref")
    private String gatewayRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PaymentTransaction of(String type, String gatewayRef) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.type = type;
        transaction.gatewayRef = gatewayRef;
        return transaction;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
