package com.ecommerce.payment.exception;

import com.ecommerce.common.constants.CorrelationIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI PROBLEM_BASE = URI.create("https://api.company.com/problems/");

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail handleNotFound(PaymentNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "payment-not-found", "Payment Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> "%s %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation Failed", detail, req);
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
