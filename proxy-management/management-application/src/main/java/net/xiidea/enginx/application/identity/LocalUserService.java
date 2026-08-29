package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.application.shared.SubjectProvider;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administering local accounts.
 *
 * <p>Every mutating method requires global admin, because a local account carries global roles and
 * anyone able to create one could grant themselves SUPER_ADMIN. The single exception is changing
 * your own password, which every account may do and which is the reason
 * {@link #changePassword} distinguishes the two cases so carefully.
 */
@Service
public class LocalUserService {

    private static final String RESOURCE_TYPE = "LOCAL_USER";

    private final LocalUserRepository users;
    private final PasswordHasher passwords;
    private final SitePermissionService permissions;
    private final SubjectProvider subjects;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final IdentityMirror mirror;
    private final Clock clock;

    public LocalUserService(LocalUserRepository users, PasswordHasher passwords,
                            SitePermissionService permissions, SubjectProvider subjects,
                            ActorProvider actors, AuditRecorder audit, IdentityMirror mirror,
                            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.permissions = permissions;
        this.subjects = subjects;
        this.actors = actors;
        this.audit = audit;
        this.mirror = mirror;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LocalUser> findAll() {
        permissions.requireGlobalAdmin();
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public LocalUser get(UUID id) {
        permissions.requireGlobalAdmin();
        return users.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }

    @Transactional
    public LocalUser create(LocalUserCommands.Create command) {
        permissions.requireGlobalAdmin();
        LocalUser.requireAcceptablePassword(command.password());

        if (users.existsByUsername(command.username())) {
            throw new ConflictException("A local user named '" + command.username() + "' already exists");
        }

        LocalUser user = LocalUser.create(UUID.randomUUID(), command.username(),
                passwords.hash(command.password()), command.email(), command.displayName(),
                command.roles(), command.groupPaths(), command.mustChangePassword(),
                actors.currentActor().username(), clock.instant());

        LocalUser saved = users.save(user);
        audit.success(AuditAction.LOCAL_USER_CREATED, RESOURCE_TYPE, saved.id(), null, snapshot(saved));
        return saved;
    }

    @Transactional
    public LocalUser update(LocalUserCommands.Update command) {
        permissions.requireGlobalAdmin();

        LocalUser user = users.findById(command.id())
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, command.id()));

        Map<String, Object> before = snapshot(user);
        user.updateProfile(command.email(), command.displayName(), command.roles(),
                command.groupPaths(), clock.instant());

        LocalUser saved = users.save(user);
        audit.success(AuditAction.LOCAL_USER_UPDATED, RESOURCE_TYPE, saved.id(), before, snapshot(saved));
        return saved;
    }

    @Transactional
    public LocalUser setEnabled(UUID id, boolean enabled) {
        permissions.requireGlobalAdmin();

        LocalUser user = users.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
        if (!enabled) {
            requireNotLastAdmin(user, "disable");
        }

        Map<String, Object> before = snapshot(user);
        user.setEnabled(enabled, clock.instant());

        LocalUser saved = users.save(user);
        audit.success(enabled ? AuditAction.LOCAL_USER_ENABLED : AuditAction.LOCAL_USER_DISABLED,
                RESOURCE_TYPE, saved.id(), before, snapshot(saved));
        return saved;
    }

    /**
     * Changes a password, either your own or — with global admin — somebody else's.
     *
     * <p>Your own requires the current password. Somebody else's does not and must not: an
     * administrator performing a reset does not have it, and requiring it would make reset
     * impossible in exactly the situation reset exists for.
     */
    @Transactional
    public void changePassword(LocalUserCommands.ChangePassword command) {
        LocalUser user = users.findById(command.id())
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, command.id()));

        boolean ownAccount = user.subjectRef().equals(subjects.currentSubject().userSubject());
        if (!ownAccount) {
            permissions.requireGlobalAdmin();
        } else if (command.currentPassword() == null
                || !passwords.matches(command.currentPassword(), user.passwordHash())) {
            // Checked even for your own account: a stolen token should not be enough to take
            // permanent ownership of the account it was stolen from.
            throw new ValidationException("currentPassword", "The current password is incorrect");
        }

        LocalUser.requireAcceptablePassword(command.newPassword());
        user.changePassword(passwords.hash(command.newPassword()), clock.instant());
        users.save(user);

        // No before/after state: both would be a password hash, and an audit trail is not the
        // place to keep a second copy of every credential the platform has ever held.
        audit.success(AuditAction.LOCAL_USER_PASSWORD_CHANGED, RESOURCE_TYPE, user.id(), null,
                Map.of("username", user.username(), "byAdministrator", String.valueOf(!ownAccount)));
    }

    @Transactional
    public void delete(UUID id) {
        permissions.requireGlobalAdmin();

        LocalUser user = users.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
        requireNotLastAdmin(user, "delete");

        Map<String, Object> before = snapshot(user);
        users.deleteById(id);
        // The mirror is a list of everyone ever seen, and nothing else prunes it. Left behind,
        // this subject keeps appearing in the grant picker as a person you can grant to -- and a
        // grant made to it would silently do nothing, because no token will ever carry it again.
        mirror.forget(user.subjectRef());
        audit.success(AuditAction.LOCAL_USER_DELETED, RESOURCE_TYPE, id, before, null);
    }

    /**
     * Refuses to remove the last way in.
     *
     * <p>Deleting or disabling the only enabled administrator locks everyone out of a running
     * platform, and the only remedy is a database edit. Cheap to check, and the check is only
     * wrong in the direction of making someone create a second admin first.
     */
    private void requireNotLastAdmin(LocalUser user, String verb) {
        boolean isAdmin = user.roles().contains(GlobalRole.SUPER_ADMIN);
        if (!isAdmin) {
            return;
        }
        long otherAdmins = users.findAll().stream()
                .filter(other -> !other.id().equals(user.id()))
                .filter(LocalUser::enabled)
                .filter(other -> other.roles().contains(GlobalRole.SUPER_ADMIN))
                .count();

        if (otherAdmins == 0) {
            throw new ConflictException("Refusing to " + verb + " the last enabled administrator. "
                    + "Create another administrator first, or nobody will be able to sign in.");
        }
    }

    private static Map<String, Object> snapshot(LocalUser user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.id().toString());
        map.put("username", user.username());
        map.put("email", String.valueOf(user.email()));
        map.put("displayName", String.valueOf(user.displayName()));
        map.put("enabled", String.valueOf(user.enabled()));
        map.put("roles", user.roles().stream().map(Enum::name).sorted().toList().toString());
        map.put("groupPaths", user.groupPaths().stream().sorted().toList().toString());
        return Map.copyOf(map);
    }
}
