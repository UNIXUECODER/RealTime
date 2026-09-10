package dev.realtime.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Stamps every response with X-Request-Id, reusing WebFlux's own per-request
 * {@code ServerHttpRequest.getId()} rather than minting a separate UUID — one
 * canonical id per request, usable to correlate a client-visible header with the
 * server-side log line for the same request (see {@link GlobalErrorHandler}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().getHeaders().add(HEADER_NAME, exchange.getRequest().getId());
        return chain.filter(exchange);
    }
}
