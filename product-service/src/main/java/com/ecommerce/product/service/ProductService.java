package com.ecommerce.product.service;

import com.ecommerce.product.dto.PageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.UUID;

public interface ProductService {
    ProductResponse create(ProductRequest request);
    ProductResponse get(UUID id);
    PageResponse<ProductResponse> list(UUID categoryId, BigDecimal maxPrice, Pageable pageable);
    ProductResponse update(UUID id, ProductRequest request);
    ProductResponse updatePrice(UUID id, BigDecimal price);
    void delete(UUID id);
}
