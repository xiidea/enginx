package net.xiidea.enginx.application.agent;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a refused enrolment in a transaction of its own.
 *
 * <p>A separate bean rather than a method on {@link AgentEnrolmentService}, and that is the whole
 * reason it exists: {@code @Transactional} is applied by a proxy, so a service calling its own
 * method gets no new transaction at all. The refusal would then be written into the transaction
 * that is about to roll back, and discarded — losing exactly the record that matters, because a
 * burst of refusals is what somebody guessing tokens looks like.
 */
@Component
public class EnrolmentRefusalRecorder {

    private final AuditRecorder audit;

    public EnrolmentRefusalRecorder(AuditRecorder audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String name, String hostname, String reason) {
        audit.denied(AuditAction.AGENT_REGISTRATION_REFUSED, "NGINX_INSTANCE", null,
                "name=" + name + " hostname=" + hostname + " reason=" + reason);
    }
}
