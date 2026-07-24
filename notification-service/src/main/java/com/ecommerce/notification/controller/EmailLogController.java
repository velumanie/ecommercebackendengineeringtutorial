package com.ecommerce.notification.controller;

import com.ecommerce.notification.dto.EmailLogResponse;
import com.ecommerce.notification.dto.PageResponse;
import com.ecommerce.notification.mapper.EmailLogMapper;
import com.ecommerce.notification.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/emails")
@RequiredArgsConstructor
public class EmailLogController {

    private final EmailLogRepository emailLogRepository;
    private final EmailLogMapper emailLogMapper;

    @GetMapping
    public PageResponse<EmailLogResponse> list(
            @RequestParam(required = false) String recipient,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        var page = recipient == null
                ? emailLogRepository.findAll(pageable)
                : emailLogRepository.findByRecipient(recipient, pageable);
        return PageResponse.from(page.map(emailLogMapper::toResponse));
    }
}
