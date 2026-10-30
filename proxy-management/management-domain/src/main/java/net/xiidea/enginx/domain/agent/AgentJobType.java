package net.xiidea.enginx.domain.agent;

/**
 * The operations a pull host collects.
 *
 * <p>The ones that change the host, plus HTTP-01 challenges and the probe after a deployment. A
 * push host is also asked questions on demand — upstream checks — and those stay push-only:
 * someone is waiting on the page for the answer. A challenge needs only to be in place within
 * seconds, and long-polling delivers a queued job in about the time of one request, so it travels
 * as a job and the publisher waits for the result. The probe after a deployment has nobody
 * waiting: its answer is added to the deployment's record whenever it comes.
 */
public enum AgentJobType {

    /** Write a bundle to the host without changing what it serves. */
    STAGE_BUNDLE,

    /** Validate the staged bundle, swap it in atomically, and reload. */
    ACTIVATE_BUNDLE,

    /** Remove a superseded bundle. */
    DISCARD_BUNDLE,

    /** Write an HTTP-01 challenge response where NGINX serves it, outside the release tree. */
    PUBLISH_ACME_CHALLENGE,

    /** Remove a challenge response once validation has finished. */
    REMOVE_ACME_CHALLENGE,

    /**
     * Probe the sites of the bundle just activated, as VERIFY does for a push host: whether each is
     * served by that bundle, and whether a protected one refuses a visitor who has not signed in.
     * Agent 0.2.2 and later; an older agent reports a job type it does not know as failed.
     */
    VERIFY_SITES
}
