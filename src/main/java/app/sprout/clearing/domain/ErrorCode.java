package app.sprout.clearing.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the clearing contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Not a member"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    CLIENT_CONFLICT(HttpStatus.CONFLICT, "Client registered differently"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
