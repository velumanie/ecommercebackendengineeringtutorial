package com.ecommerce.order.config;

import com.ecommerce.common.constants.CorrelationIdConstants;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignClientConfig {

    @Bean
    public RequestInterceptor correlationIdPropagationInterceptor() {
        return template -> {
            String correlationId = MDC.get(CorrelationIdConstants.MDC_KEY);
            if (correlationId != null) {
                template.header(CorrelationIdConstants.HEADER, correlationId);
            }
        };
    }

    // inventory-service and payment-service each independently validate the caller's JWT
    // (the gateway isn't a trust boundary — see JwtAuthenticationGlobalFilter), so the
    // token from the inbound order-service request must be forwarded on outbound Feign calls.
    @Bean
    public RequestInterceptor bearerTokenPropagationInterceptor() {
        return template -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                String authorization = servletAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization != null) {
                    template.header(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
        };
    }
}
