package net.xiidea.enginx.application.agent;

import net.xiidea.enginx.domain.agent.AgentSecret;
import net.xiidea.enginx.domain.agent.AgentToken;
import net.xiidea.enginx.domain.agent.AgentTokenRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Resolves an agent's bearer token to the host that holds it.
 *
 * <p>This is the whole of a pull-mode host's identity, and therefore the thing standing between an
 * attacker and every site's private key — a configuration bundle contains them (risk R1). It is
 * looked up by digest, so a token that is not in the table cannot be distinguished from one that
 * never existed, and neither reveals anything.
 */
@Service
public class AgentAuthenticationService {

    private final AgentTokenRepository tokens;
    private final NginxInstanceRepository instances;
    private final Clock clock;

    public AgentAuthenticationService(AgentTokenRepository tokens, NginxInstanceRepository instances,
                                      Clock clock) {
        this.tokens = tokens;
        this.instances = instances;
        this.clock = clock;
    }

    /**
     * @return the instance this token belongs to, or empty for any reason at all
     */
    @Transactional
    public Optional<NginxInstance> authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }

        Optional<AgentToken> found = tokens.findByTokenHash(AgentSecret.hash(presentedToken))
                .filter(AgentToken::isUsable);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        AgentToken token = found.get();
        // Stamped on every call, so a credential nobody is using becomes visible. A token last
        // presented a month ago belongs to a host that is gone.
        token.used(clock.instant());
        tokens.save(token);

        return instances.findById(token.nginxInstanceId());
    }
}
