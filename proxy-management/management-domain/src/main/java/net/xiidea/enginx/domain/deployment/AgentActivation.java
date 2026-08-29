package net.xiidea.enginx.domain.deployment;

/**
 * @param noop        the requested bundle was already active, so nothing was changed
 * @param rolledBack  the reload failed and the agent restored the previous configuration itself
 */
public record AgentActivation(
        String bundleId,
        String previousBundleId,
        String testOutput,
        String nginxVersion,
        boolean noop,
        boolean rolledBack) {
}
