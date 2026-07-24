package com.ecommerce.notification.mapper;

import com.ecommerce.notification.dto.EmailLogResponse;
import com.ecommerce.notification.entity.EmailLog;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EmailLogMapper {
    EmailLogResponse toResponse(EmailLog emailLog);
}
