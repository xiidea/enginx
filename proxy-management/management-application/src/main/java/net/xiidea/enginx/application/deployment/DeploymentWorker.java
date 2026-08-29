package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.outbox.OutboxMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one outbox message in its own transaction, holding the lock for the target instance.
 *
 * <p>A separate bean from the poller so the transaction and the lock are established by a proxy
 * boundary. Calling this from inside the poller directly would skip both, and the lock would
 * quietly do nothing.
 */
@Component
public class DeploymentWorker {

    private final DeploymentDispatcher dispatcher;
    private final DeploymentRepository deployments;
    private final InstanceLock lock;

    public DeploymentWorker(DeploymentDispatcher dispatcher, DeploymentRepository deployments, InstanceLock lock) {
        this.dispatcher = dispatcher;
        this.deployments = deployments;
        this.lock = lock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean run(OutboxMessage message) {
        if (!OutboxMessage.DISPATCH_DEPLOYMENT.equals(message.messageType())) {
            // Unknown message types are dropped rather than retried forever; a type this version
            // does not understand will not start working on the sixth attempt.
            return true;
        }

        Deployment deployment = deployments.findById(message.aggregateId()).orElse(null);
        if (deployment == null) {
            return true;
        }

        // Held until this transaction commits, which covers render, upload and activation.
        lock.lockForUpdate(deployment.nginxInstanceId());

        return dispatcher.dispatch(message.aggregateId());
    }
}
