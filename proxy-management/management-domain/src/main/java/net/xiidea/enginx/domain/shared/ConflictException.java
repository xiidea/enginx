package net.xiidea.enginx.domain.shared;

/** The requested change conflicts with existing state. Maps to HTTP 409. */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
