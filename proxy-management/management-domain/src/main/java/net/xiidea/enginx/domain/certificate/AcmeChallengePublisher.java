package net.xiidea.enginx.domain.certificate;

/**
 * Publishes an HTTP-01 challenge response where the authority will look for it.
 *
 * <p>A port because "where the authority will look" is a deployment question the domain should
 * not answer. The implementation publishes to every managed NGINX host: the token is a public
 * value, and doing so avoids the platform having to know which host a domain's DNS points at.
 */
public interface AcmeChallengePublisher {

    void publish(String token, String authorization);

    /** Removes the response once validation has finished, successfully or not. */
    void withdraw(String token);
}
