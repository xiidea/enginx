package net.xiidea.enginx.domain.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRegistrationTokenRepository {

    AgentRegistrationToken save(AgentRegistrationToken token);

    Optional<AgentRegistrationToken> findById(UUID id);

    /**
     * The token matching this digest, if any.
     *
     * <p>A lookup by hash rather than a scan-and-compare is the whole reason these are digested
     * rather than bcrypted.
     */
    Optional<AgentRegistrationToken> findByTokenHash(String tokenHash);

    List<AgentRegistrationToken> findAll();

    void deleteById(UUID id);
}
