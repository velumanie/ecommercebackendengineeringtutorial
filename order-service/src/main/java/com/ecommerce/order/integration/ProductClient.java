package com.ecommerce.order.integration;

import com.ecommerce.order.integration.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "product-service", configuration = com.ecommerce.order.config.FeignClientConfig.class)
public interface ProductClient {

    @GetMapping("/api/v1/products/{productId}")
    ProductResponse getProduct(@PathVariable("productId") UUID productId);
}
