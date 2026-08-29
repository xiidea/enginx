package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.application.shared.IdentityMirror;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns "who owns this" into addresses.
 *
 * <p>Owner plus operators, rather than one or the other. The owner is the person who can decide
 * whether a site still needs to exist; the operators are the ones who will be paged when it breaks
 * at 3am. Estate-wide conditions — an agent that has gone quiet, a dead outbox message — have no
 * owner at all, which is exactly why the operator list is not optional.
 *
 * <p>The identity mirror is read here for an address. That is display, not authorization: a stale
 * mirror can misdirect an email, and can never widen anyone's access.
 */
@Component
public class RecipientResolver {

    private final IdentityMirror mirror;
    private final NotificationProperties properties;

    public RecipientResolver(IdentityMirror mirror, NotificationProperties properties) {
        this.mirror = mirror;
        this.properties = properties;
    }

    /**
     * @param ownerUsername the resource's owner, or null for an estate-wide condition
     */
    public Set<String> resolve(String ownerUsername) {
        Set<String> addresses = new LinkedHashSet<>(properties.operatorAddresses());

        if (properties.notifyOwner() && ownerUsername != null && !ownerUsername.isBlank()) {
            emailFor(ownerUsername).ifPresent(addresses::add);
        }
        return addresses;
    }

    /**
     * The mirror stores what the identity provider last reported. A user who has never signed in,
     * or who has no email address on their account, simply is not reachable — the operator list
     * still is, so the notification goes out rather than being dropped for want of one recipient.
     *
     * <p>A username can match more than one subject, since two providers may both have an
     * {@code admin}. The mirror returns them most recently seen first, and the first with a
     * usable address wins: any choice is a guess, and the freshest one is the best guess.
     */
    private java.util.Optional<String> emailFor(String username) {
        return mirror.findByUsername(username).stream()
                .map(IdentityMirror.MirroredUser::email)
                .filter(email -> email != null && email.contains("@"))
                .map(email -> email.toLowerCase(Locale.ROOT))
                .findFirst();
    }
}
