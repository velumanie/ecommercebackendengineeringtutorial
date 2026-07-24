package com.ecommerce.inventory.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "warehouses")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false)
    private String location;

    public static Warehouse create(String code, String location) {
        Warehouse warehouse = new Warehouse();
        warehouse.code = code;
        warehouse.location = location;
        return warehouse;
    }
}
