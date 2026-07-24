package com.ecommerce.notification.exception;

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

    private static final URI PROBLEM_BASE = URI.create("https://api.company.com/problems/internal-error");

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(PROBLEM_BASE);
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(req.getRequestURI()));
        problem.setProperty("correlationId", MDC.get(CorrelationIdConstants.MDC_KEY));
        return problem;
    }
}
