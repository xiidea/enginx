package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.DomainException;

/** The agent could not be reached, or answered in a way the platform does not understand. */
public class AgentException extends DomainException {

    private final boolean retryable;

    public AgentException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AgentException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** Transport problems are worth another attempt; a rejected request is not. */
    public boolean isRetryable() {
        return retryable;
    }
}
