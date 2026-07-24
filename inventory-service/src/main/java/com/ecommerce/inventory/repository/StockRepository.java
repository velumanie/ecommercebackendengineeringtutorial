package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository extends JpaRepository<Stock, UUID> {
    List<Stock> findByProductId(UUID productId);
    Optional<Stock> findFirstByProductIdOrderByQuantityOnHandDesc(UUID productId);
}
