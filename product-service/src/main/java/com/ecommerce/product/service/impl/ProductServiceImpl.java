package com.ecommerce.product.service.impl;

import com.ecommerce.product.dto.PageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.mapper.ProductMapper;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;

    @Override
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Category category = request.categoryId() == null ? null : categoryRepository.findById(request.categoryId()).orElse(null);
        Product product = Product.create(request.sku(), request.name(), request.description(), request.price(), category);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return productMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(UUID categoryId, BigDecimal maxPrice, Pageable pageable) {
        Specification<Product> spec = Specification
                .<Product>where((root, q, cb) -> categoryId == null ? null : cb.equal(root.get("category").get("id"), categoryId))
                .and((root, q, cb) -> maxPrice == null ? null : cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        return PageResponse.from(productRepository.findAll(spec, pageable).map(productMapper::toResponse));
    }

    @Override
    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = findOrThrow(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        if (request.categoryId() != null) {
            categoryRepository.findById(request.categoryId()).ifPresent(product::setCategory);
        }
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductResponse updatePrice(UUID id, BigDecimal price) {
        Product product = findOrThrow(id);
        product.setPrice(price);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Product product = findOrThrow(id);
        product.setStatus(com.ecommerce.product.entity.ProductStatus.DISCONTINUED);
        productRepository.save(product);
    }

    private Product findOrThrow(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }
}
