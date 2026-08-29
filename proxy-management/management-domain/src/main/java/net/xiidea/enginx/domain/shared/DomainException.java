package net.xiidea.enginx.domain.shared;

/** Base type for every error the domain raises. Carries no framework coupling. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
