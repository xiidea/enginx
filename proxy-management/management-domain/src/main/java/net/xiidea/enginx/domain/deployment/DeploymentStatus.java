package net.xiidea.enginx.domain.deployment;

public enum DeploymentStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
