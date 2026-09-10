package dev.realtime.ingest;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;

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
 * M1 scope: accept a webhook, get it into the stream, exactly once.
 * M3 adds: reject malformed JSON up front (400), before it ever reaches the filter
 * engine or gets stored — a channel filter rule assumes valid JSON, so validating here
 * avoids a parse failure surfacing as an unhandled 500 deeper in the pipeline instead.
 *
 * <p>Deliberately out of scope here (see roadmap M1): no API key check on {@code
 * channelId} (any value is accepted — auth arrives in M5).
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
                    if (!isValidJson(payload)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Webhook body must be valid JSON"));
                    }
                    return ingestService.ingest(channelId, payload);
                })
                // Accepted, duplicate, or filtered — the sender always sees the same 202;
                // from their side, every outcome here means "got it, stop retrying."
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).<Void>build());
    }

    private boolean isValidJson(String payload) {
        try {
            // Reuses json-path's own parser rather than Jackson directly — after the
            // Jackson 3 restructuring, better to lean on a code path already being
            // exercised for filtering than on an uncertain corner of the new hierarchy.
            JsonPath.parse(payload);
            return true;
        } catch (InvalidJsonException e) {
            return false;
        }
    }
}

