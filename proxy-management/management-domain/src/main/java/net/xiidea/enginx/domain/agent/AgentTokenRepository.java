package net.xiidea.enginx.domain.agent;

import java.util.Optional;
import java.util.UUID;

public interface AgentTokenRepository {

    AgentToken save(AgentToken token);

    Optional<AgentToken> findByTokenHash(String tokenHash);

    /** The live token for an instance, if it has one. */
    Optional<AgentToken> findActiveByInstanceId(UUID nginxInstanceId);
}
