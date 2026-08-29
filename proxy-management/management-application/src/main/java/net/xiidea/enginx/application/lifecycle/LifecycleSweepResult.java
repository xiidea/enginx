package net.xiidea.enginx.application.lifecycle;

/**
 * @param claimed         sites this worker took; a full batch means more are waiting
 * @param instancesQueued NGINX instances whose configuration must be republished
 */
public record LifecycleSweepResult(int claimed, int expired, int activated, int instancesQueued) {

    private static final LifecycleSweepResult EMPTY = new LifecycleSweepResult(0, 0, 0, 0);

    public static LifecycleSweepResult empty() {
        return EMPTY;
    }

    public boolean changedAnything() {
        return expired > 0 || activated > 0;
    }
}
