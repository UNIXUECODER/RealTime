package dev.realtime.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * M1 scope only: accept a webhook, get it into the stream, exactly once.
 *
 * <p>Deliberately out of scope here (see roadmap M1): no API key check on {@code
 * channelId} (any value is accepted — auth arrives in M5), no channel-level filtering
 * (M3), no structured error envelope or 413 handling (M3).
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final IngestService ingestService;

    public WebhookController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{channelId}")
    public Mono<ResponseEntity<Void>> receive(
            @PathVariable String channelId,
            @RequestBody Mono<String> body) {

        return body
                .defaultIfEmpty("")
                .flatMap(payload -> {
                    if (payload.isBlank()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Webhook body must not be empty"));
                    }
                    return ingestService.ingest(channelId, payload);
                })
                // Whether this was a fresh event or a deduped retry, the sender sees the
                // same 202 — from their side, both outcomes mean "got it, stop retrying."
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).<Void>build());
    }
}
