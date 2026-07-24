package com.ecommerce.payment.dto;

import java.util.UUID;

public record PaymentAuthorizationResponse(UUID paymentId, boolean authorized, String declineReason) {
}
