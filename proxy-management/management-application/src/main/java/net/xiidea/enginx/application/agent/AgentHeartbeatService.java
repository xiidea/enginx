package net.xiidea.enginx.application.agent;

import net.xiidea.enginx.application.nginx.InstanceObserver;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A pull host reporting on itself.
 *
 * <p>The inverse of the push poll, and slightly better than it: the host reports the moment
 * something changes rather than waiting to be asked, so drift is caught on the host's own schedule
 * instead of the sweep's. The interpretation is deliberately not duplicated — this hands the
 * status to the same {@link InstanceObserver} the push path uses, so both models agree on what
 * ONLINE and DEGRADED mean.
 */
@Service
public class AgentHeartbeatService {

    private final InstanceObserver observer;

    public AgentHeartbeatService(InstanceObserver observer) {
        this.observer = observer;
    }

    @Transactional
    public void accept(NginxInstance instance, AgentStatus status) {
        observer.record(instance, status);
    }
}
