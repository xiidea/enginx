package net.xiidea.enginx.domain.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocalUserRepository {

    LocalUser save(LocalUser user);

    Optional<LocalUser> findById(UUID id);

    /** Lookup for the login path. The username is matched exactly, already lowercased. */
    Optional<LocalUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<LocalUser> findAll();

    /** Whether any account exists at all, which decides whether to bootstrap one. */
    long count();

    void deleteById(UUID id);
}
