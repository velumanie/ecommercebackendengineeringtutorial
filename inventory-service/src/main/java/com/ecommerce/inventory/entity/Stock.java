package com.ecommerce.inventory.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public static Stock create(UUID productId, Warehouse warehouse, int quantityOnHand) {
        Stock stock = new Stock();
        stock.productId = productId;
        stock.warehouse = warehouse;
        stock.quantityOnHand = quantityOnHand;
        return stock;
    }

    public int available() {
        return quantityOnHand - quantityReserved;
    }

    public void reserve(int quantity) {
        if (available() < quantity) {
            throw new IllegalStateException("Insufficient stock");
        }
        quantityReserved += quantity;
        updatedAt = Instant.now();
    }

    public void release(int quantity) {
        quantityReserved = Math.max(0, quantityReserved - quantity);
        updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }
}
