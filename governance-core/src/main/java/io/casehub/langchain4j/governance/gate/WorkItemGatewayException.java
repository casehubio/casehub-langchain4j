package io.casehub.langchain4j.governance.gate;

/** A remote casehub-work call failed. Carries the HTTP status, never the response body or the token. */
public class WorkItemGatewayException extends RuntimeException {

    private final int status;

    public WorkItemGatewayException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status, or {@code -1} for an I/O failure or timeout. */
    public int status() {
        return status;
    }
}
