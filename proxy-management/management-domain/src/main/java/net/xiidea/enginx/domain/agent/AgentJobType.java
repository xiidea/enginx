package net.xiidea.enginx.domain.agent;

/**
 * The operations a pull host collects.
 *
 * <p>The ones that change the host, plus HTTP-01 challenges. A push host is also asked questions —
 * verify, upstream checks — and those stay push-only: each needs an answer, not just an effect.
 * A challenge needs only to be in place within seconds, and long-polling delivers a queued job in
 * about the time of one request, so it travels as a job and the publisher waits for the result.
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
    REMOVE_ACME_CHALLENGE
}
