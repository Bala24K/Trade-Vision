package io.tradevision.gateway.api;

/**
 * Request rejected before it reaches the engine. Rendered as {@code 400 {"error": code, "field": field, "message"}}.
 * Extends {@link IllegalArgumentException} so existing 400 handling still applies if it is wrapped.
 */
public final class ValidationException extends IllegalArgumentException {
    public final String code;
    public final String field;

    public ValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }
}
