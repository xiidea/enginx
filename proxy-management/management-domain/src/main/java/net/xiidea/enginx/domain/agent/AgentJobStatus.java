package net.xiidea.enginx.domain.agent;

public enum AgentJobStatus {

    /** Waiting for its host to ask. */
    QUEUED,

    /**
     * Handed to a host, which has until the lease expires to report back.
     *
     * <p>The lease is what makes a dead agent harmless: the job returns to QUEUED and is collected
     * again rather than stranding whatever asked for it.
     */
    LEASED,

    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
