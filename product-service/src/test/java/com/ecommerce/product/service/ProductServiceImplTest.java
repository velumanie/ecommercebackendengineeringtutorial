package com.ecommerce.product.service;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.entity.ProductStatus;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.mapper.ProductMapper;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductMapper productMapper;

    @InjectMocks
    private ProductServiceImpl productService;

    @Test
    void create_savesProduct_withoutCategory() {
        var request = new ProductRequest("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productMapper.toResponse(any(Product.class))).thenReturn(
                new ProductResponse(UUID.randomUUID(), "SKU-1", "Widget", "A widget",
                        new BigDecimal("19.99"), null, ProductStatus.ACTIVE));

        ProductResponse response = productService.create(request);

        assertThat(response.sku()).isEqualTo("SKU-1");
        verify(categoryRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void get_throws_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.get(id)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void updatePrice_updatesOnlyPrice() {
        UUID id = UUID.randomUUID();
        Product product = Product.create("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(
                new ProductResponse(id, "SKU-1", "Widget", "A widget", new BigDecimal("24.99"), null, ProductStatus.ACTIVE));

        ProductResponse response = productService.updatePrice(id, new BigDecimal("24.99"));

        assertThat(product.getPrice()).isEqualByComparingTo("24.99");
        assertThat(response.price()).isEqualByComparingTo("24.99");
    }

    @Test
    void delete_setsStatusDiscontinued() {
        UUID id = UUID.randomUUID();
        Product product = Product.create("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        productService.delete(id);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
        verify(productRepository).save(product);
    }
}
