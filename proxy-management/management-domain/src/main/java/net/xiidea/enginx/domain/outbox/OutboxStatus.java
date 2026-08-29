package net.xiidea.enginx.domain.outbox;

public enum OutboxStatus {
    NEW,
    IN_PROGRESS,
    DONE,
    /** Exhausted its attempts. Needs a person; it will not be retried. */
    DEAD
}
