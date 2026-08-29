package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.infrastructure.persistence.entity.LocalUserEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.LocalUserJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class LocalUserRepositoryAdapter implements LocalUserRepository {

    private final LocalUserJpaRepository repository;

    public LocalUserRepositoryAdapter(LocalUserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public LocalUser save(LocalUser user) {
        LocalUserEntity entity = repository.findById(user.id())
                .orElseGet(() -> new LocalUserEntity(user.id()));

        entity.apply(user.username(), user.passwordHash(), user.email(), user.displayName(),
                user.enabled(), user.mustChangePassword(),
                user.roles().stream().map(Enum::name).collect(Collectors.toSet()),
                user.groupPaths(), user.lastLoginAt(), user.createdBy(),
                user.createdAt(), user.updatedAt());

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalUser> findById(UUID id) {
        return repository.findById(id).map(LocalUserRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return repository.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .map(LocalUserRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return username != null && repository.existsByUsername(username.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalUser> findAll() {
        return repository.findAll(org.springframework.data.domain.Sort.by("username"))
                .stream().map(LocalUserRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private static LocalUser toDomain(LocalUserEntity e) {
        Set<GlobalRole> roles = e.getRoles().stream()
                .map(GlobalRole::from)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        return LocalUser.rehydrate(e.getId(), e.getUsername(), e.getPasswordHash(), e.getEmail(),
                e.getDisplayName(), e.isEnabled(), e.isMustChangePassword(), roles,
                Set.copyOf(e.getGroupPaths()), e.getLastLoginAt(), e.getCreatedBy(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
