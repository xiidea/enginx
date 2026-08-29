package net.xiidea.enginx.application.shared;

/** Port giving the application layer the caller's identity without depending on the web stack. */
public interface ActorProvider {

    Actor currentActor();
}
