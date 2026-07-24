package com.ecommerce.order.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductResponse(UUID id, String sku, String name, BigDecimal price) {
}
