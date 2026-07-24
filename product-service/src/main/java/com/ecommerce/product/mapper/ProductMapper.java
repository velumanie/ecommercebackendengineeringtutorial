package com.ecommerce.product.mapper;

import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "categoryId", source = "category.id")
    ProductResponse toResponse(Product product);
}
