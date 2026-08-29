package net.xiidea.enginx.domain.deployment;

/**
 * The steps of a deployment, recorded individually so a failure says where it happened.
 *
 * <p>The ordering matters and is enforced by the agent, not merely documented here: nothing can
 * reach {@link #RELOAD} without {@link #VALIDATE} having succeeded first.
 */
public enum DeploymentPhase {
    RENDER,
    UPLOAD,
    VALIDATE,
    ACTIVATE,
    RELOAD,
    VERIFY,
    ROLLBACK
}
