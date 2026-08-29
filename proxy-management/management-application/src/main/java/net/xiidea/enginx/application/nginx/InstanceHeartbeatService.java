package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class InstanceHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeatService.class);

    private final NginxInstanceRepository instances;
    private final InstanceObserver observer;

    public InstanceHeartbeatService(NginxInstanceRepository instances, InstanceObserver observer) {
        this.instances = instances;
        this.observer = observer;
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
            if (observer.poll(instance)) {
                reachable++;
            }
        }
        if (!all.isEmpty() && reachable < all.size()) {
            log.info("Heartbeat: {} of {} instances reachable", reachable, all.size());
        }
        return reachable;
    }
}
