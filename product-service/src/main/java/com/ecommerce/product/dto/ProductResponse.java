package com.ecommerce.product.dto;

import com.ecommerce.product.entity.ProductStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID id, String sku, String name, String description, BigDecimal price,
        UUID categoryId, ProductStatus status) {
}
