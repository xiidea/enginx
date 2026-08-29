package net.xiidea.enginx.domain.shared;

/** A supplied value violates a domain invariant. Maps to HTTP 422. */
public class ValidationException extends DomainException {

    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public ValidationException(String message) {
        this(null, message);
    }

    public String field() {
        return field;
    }
}
