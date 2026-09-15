package dev.realtime.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Spring's static-resource handler serves {@code index.html} for an exact match on
 * {@code /}, but knows nothing about React Router's client-side paths — a hard refresh
 * on {@code /app/channels/abc123} would 404 without this, since no file exists at that
 * path on disk. This just returns the same shell for any {@code /app/**} route (and
 * bare {@code /app}); React Router takes over from there once the bundle loads.
 *
 * {@code @RestController} (not plain {@code @Controller}) for the same reason every
 * other controller here uses it — a {@code Resource} return needs to be written as the
 * response body, not resolved as a view name.
 */
@RestController
public class SpaController {

    @GetMapping({"/app", "/app/**"})
    public Mono<Resource> index() {
        return Mono.just(new ClassPathResource("static/index.html"));
    }
}
