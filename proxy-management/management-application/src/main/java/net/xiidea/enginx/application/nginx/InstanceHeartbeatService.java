package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Asks each agent how it is, on a timer.
 *
 * <p>Without this, {@code last_seen_at} would only ever be written by a deployment, so an estate
 * that is simply working — nothing to deploy, everything serving — would look identical to one
 * where every agent had died. A reachability check that cannot tell those apart is not a check; it
 * decays into a permanent warning and then gets ignored, which is worse than not having it.
 *
 * <p>The poll is a status call rather than a ping: it reports whether NGINX is actually running
 * and whether its configuration still passes {@code nginx -t}, which is the difference between an
 * agent that answers and a host that is serving.
 *
 * <p>A pull host is not dialled — there is nothing to dial. For those this sweep does the opposite
 * job: it looks at how long ago the host last called in and marks it offline once that exceeds the
 * silence threshold. Same question, asked from the only end that can ask it.
 */
@Service
public class InstanceHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeatService.class);

    private final NginxInstanceRepository instances;
    private final InstanceObserver observer;
    private final Duration silenceThreshold;

    public InstanceHeartbeatService(NginxInstanceRepository instances, InstanceObserver observer,
                                    @Value("${enginx.observability.agent-silence:5m}") Duration silenceThreshold) {
        this.instances = instances;
        this.observer = observer;
        this.silenceThreshold = silenceThreshold;
    }

    /**
     * Polls every registered instance.
     *
     * @return how many answered
     */
    public int pollAll() {
        List<NginxInstance> all = instances.findAll();
        int reachable = 0;
        for (NginxInstance instance : all) {
            boolean healthy = instance.connectivityMode().isPull()
                    ? observer.recordSilence(instance, silenceThreshold)
                    : observer.poll(instance);
            if (healthy) {
                reachable++;
            }
        }
        if (!all.isEmpty() && reachable < all.size()) {
            log.info("Heartbeat: {} of {} instances reachable", reachable, all.size());
        }
        return reachable;
    }
}
