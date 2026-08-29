package net.xiidea.enginx.domain.agent;

/**
 * The operations a pull host collects.
 *
 * <p>Only the ones that change the host. A push host is also asked questions — verify, upstream
 * checks, ACME challenges — and those stay push-only for now: each needs a reply within a
 * particular window, which a queue does not promise.
 */
public enum AgentJobType {

    /** Write a bundle to the host without changing what it serves. */
    STAGE_BUNDLE,

    /** Validate the staged bundle, swap it in atomically, and reload. */
    ACTIVATE_BUNDLE,

    /** Remove a superseded bundle. */
    DISCARD_BUNDLE
}
