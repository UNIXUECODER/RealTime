package dev.realtime.web;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.PayloadTooLargeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Central error mapping for M3. Every error response carries the same X-Request-Id the
 * client already sees in the response header (RequestIdWebFilter), and every handler
 * logs that same id alongside the failure — so a client-reported error and a server
 * log line can always be tied together, per M3's exit criteria.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatus(
            ResponseStatusException ex, ServerWebExchange exchange) {

        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.value() == HttpStatus.CONTENT_TOO_LARGE.value()) {
            log.warn("Request body exceeded size limit: {} {} [requestId={}]",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    exchange.getRequest().getId());

            return respond(exchange, HttpStatus.CONTENT_TOO_LARGE,
                    "Request body exceeds the configured size limit");
        }

        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        log.warn("Request rejected: {} {} -> {} [requestId={}] {}",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                status.value(), exchange.getRequest().getId(), reason);

        return respond(exchange, status, reason);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public Mono<ResponseEntity<ErrorResponse>> handlePayloadTooLarge(
            PayloadTooLargeException ex, ServerWebExchange exchange) {

        log.warn("Request body exceeded size limit: {} {} [requestId={}]",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getRequest().getId());

        return respond(exchange, HttpStatus.CONTENT_TOO_LARGE,
                "Request body exceeds the configured size limit");
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDataBufferLimit(
            DataBufferLimitException ex, ServerWebExchange exchange) {

        log.warn("Request body exceeded size limit: {} {} [requestId={}]",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getRequest().getId());

        return respond(exchange, HttpStatus.CONTENT_TOO_LARGE,
                "Request body exceeds the configured size limit");
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUnexpected(
            Exception ex, ServerWebExchange exchange) {

        log.error("Unhandled exception: {} {} [requestId={}]",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getRequest().getId(), ex);

        return respond(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private Mono<ResponseEntity<ErrorResponse>> respond(
            ServerWebExchange exchange, HttpStatus status, String message) {

        ErrorResponse body = new ErrorResponse(
                exchange.getRequest().getId(), status.value(), status.getReasonPhrase(),
                message, Instant.now());

        return Mono.just(ResponseEntity.status(status).body(body));
    }
}
