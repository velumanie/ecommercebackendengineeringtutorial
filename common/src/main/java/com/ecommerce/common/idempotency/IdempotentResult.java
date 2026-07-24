package com.ecommerce.common.idempotency;

/**
 * Wraps a service-layer result with whether this call actually performed the operation
 * or returned a previously-recorded result for a repeated {@code Idempotency-Key}. Controllers
 * use {@code created} to pick the HTTP status (e.g. 201 for a fresh resource, 200 for a replay).
 */
public record IdempotentResult<T>(T body, boolean created) {

    public static <T> IdempotentResult<T> created(T body) {
        return new IdempotentResult<>(body, true);
    }

    public static <T> IdempotentResult<T> replayed(T body) {
        return new IdempotentResult<>(body, false);
    }
}
