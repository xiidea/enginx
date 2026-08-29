package net.xiidea.enginx.domain.shared;

import java.util.UUID;

/** The referenced aggregate does not exist. Maps to HTTP 404. */
public class NotFoundException extends DomainException {

    private final String resourceType;
    private final UUID resourceId;

    public NotFoundException(String resourceType, UUID resourceId) {
        super(resourceType + " " + resourceId + " was not found");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String resourceType() {
        return resourceType;
    }

    public UUID resourceId() {
        return resourceId;
    }
}
