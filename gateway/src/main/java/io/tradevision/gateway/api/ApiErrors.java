package io.tradevision.gateway.api;

import io.tradevision.gateway.engine.EngineHost;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/** Maps failures to explicit status codes: 400 bad input, 503 backpressure, 504 engine timeout. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Map<String, String>> invalid(ValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.code, "field", e.field, "message", e.getMessage()));
    }

    /** Submitted but not yet durable when the caller stopped waiting. The command was NOT cancelled. */
    @ExceptionHandler(EngineHost.OutcomePendingException.class)
    ResponseEntity<Map<String, String>> pending(EngineHost.OutcomePendingException e) {
        return ResponseEntity.status(504).body(Map.of("error", "OUTCOME_PENDING", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(EngineHost.OverloadedException.class)
    ResponseEntity<Map<String, String>> overloaded(EngineHost.OverloadedException e) {
        return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of("error", "OVERLOADED"));
    }

    @ExceptionHandler({TimeoutException.class})
    ResponseEntity<Map<String, String>> timeout(Exception e) {
        return ResponseEntity.status(504).body(Map.of("error", "ENGINE_TIMEOUT"));
    }

    @ExceptionHandler(CompletionException.class)
    ResponseEntity<Map<String, String>> unwrap(CompletionException e) {
        Throwable c = e.getCause();
        if (c instanceof EngineHost.OverloadedException o) return overloaded(o);
        if (c instanceof EngineHost.OutcomePendingException p) return pending(p);
        if (c instanceof ValidationException v) return invalid(v);
        if (c instanceof TimeoutException t) return timeout(t);
        if (c instanceof IllegalArgumentException i) return badRequest(i);
        return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(c)));
    }
}
