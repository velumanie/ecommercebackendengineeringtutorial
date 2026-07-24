package com.ecommerce.inventory.exception;

import com.ecommerce.common.constants.CorrelationIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI PROBLEM_BASE = URI.create("https://api.company.com/problems/");

    @ExceptionHandler(StockNotFoundException.class)
    public ProblemDetail handleNotFound(StockNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "stock-not-found", "Stock Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficient(InsufficientStockException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "insufficient-stock", "Insufficient Stock", ex.getMessage(), req);
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
