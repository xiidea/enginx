package net.xiidea.enginx.domain.deployment;

/**
 * {@code nginx -t} rejected the staged configuration.
 *
 * <p>Never retryable: the same bytes will fail the same way, so retrying would burn attempts and
 * delay the alert. It needs a person. Crucially, nothing on the host changed — the previous
 * configuration is still being served.
 */
public class AgentValidationFailedException extends AgentException {

    private final String testOutput;

    public AgentValidationFailedException(String testOutput) {
        super("NGINX rejected the configuration", false);
        this.testOutput = testOutput;
    }

    public String testOutput() {
        return testOutput;
    }
}
