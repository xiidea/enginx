package net.xiidea.enginx.domain.agent;

/**
 * What the host reports after running a job.
 *
 * <p>Deliberately the same information the push path's activation response carries, so the
 * deployment record ends up identical whichever way the work reached the host.
 *
 * @param validationFailed {@code nginx -t} rejected the configuration. Distinct from any other
 *                         failure because nothing changed on the host and a retry of identical
 *                         bytes would fail identically — a person has to look.
 * @param verifications    for a {@link AgentJobType#VERIFY_SITES} job, what each site answered;
 *                         null for every other job
 */
public record AgentJobResult(boolean succeeded, boolean validationFailed, String testOutput,
                             String nginxVersion, String previousBundleId, boolean noop,
                             boolean rolledBack, String error,
                             java.util.List<net.xiidea.enginx.domain.deployment.SiteVerification> verifications) {

    public AgentJobResult(boolean succeeded, boolean validationFailed, String testOutput,
                          String nginxVersion, String previousBundleId, boolean noop,
                          boolean rolledBack, String error) {
        this(succeeded, validationFailed, testOutput, nginxVersion, previousBundleId, noop, rolledBack, error, null);
    }

    public static AgentJobResult ok() {
        return new AgentJobResult(true, false, null, null, null, false, false, null);
    }
}
