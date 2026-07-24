package com.ecommerce.order.exception;

import com.ecommerce.common.constants.CorrelationIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * RFC 9457 problem responses via Spring's native {@link ProblemDetail} — returning it
 * directly from an {@code @ExceptionHandler} makes Spring MVC set both the response
 * status and the {@code application/problem+json} content type from the object itself.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://api.company.com/problems/");

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    public ProblemDetail handleInventory(InventoryUnavailableException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "inventory-unavailable", "Inventory Unavailable", ex.getMessage(), req);
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ProblemDetail handlePayment(PaymentDeclinedException ex, HttpServletRequest req) {
        return problem(HttpStatus.PAYMENT_REQUIRED, "payment-declined", "Payment Declined", ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail handleInvalidState(InvalidOrderStateException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "invalid-order-state", "Invalid Order State", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> "%s %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation Failed", detail, req);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error handling {} {}", req.getMethod(), req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred", req);
    }

    private ProblemDetail problem(HttpStatus status, String typeSuffix, String title, String detail,
                                   HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(PROBLEM_BASE.resolve(typeSuffix));
        problem.setTitle(title);
        problem.setInstance(URI.create(req.getRequestURI()));
        problem.setProperty("correlationId", MDC.get(CorrelationIdConstants.MDC_KEY));
        return problem;
    }
}
