package net.xiidea.enginx.domain.agent;

import java.util.UUID;

/**
 * What a job tells its host to act on.
 *
 * <p>A reference, not the thing itself. The bundle carries every site's private key and can be
 * megabytes; putting it in the queue row would mean the keys sit in a second table, and every
 * listing of pending work would carry them. The host fetches the bundle separately, over a call
 * authorised to that host alone.
 *
 * @param idempotencyKey the deployment's key, so a replayed job reaches the same state rather
 *                       than a different one
 * @param reload         whether activation should reload NGINX, or validate and stage only
 * @param acmeToken      the HTTP-01 token, for a challenge job. Public: the authority fetches it
 *                       over plain HTTP moments later, so it is no secret to keep out of the queue
 * @param acmeAuthorization the key authorization served for that token, likewise public
 */
public record AgentJobPayload(UUID bundleId, String idempotencyKey, boolean reload,
                              String acmeToken, String acmeAuthorization) {

    public AgentJobPayload(UUID bundleId, String idempotencyKey, boolean reload) {
        this(bundleId, idempotencyKey, reload, null, null);
    }

    public static AgentJobPayload forBundle(UUID bundleId, String idempotencyKey) {
        return new AgentJobPayload(bundleId, idempotencyKey, true);
    }

    public static AgentJobPayload forAcmeChallenge(String token, String authorization) {
        return new AgentJobPayload(null, null, false, token, authorization);
    }

    public static AgentJobPayload forAcmeWithdrawal(String token) {
        return new AgentJobPayload(null, null, false, token, null);
    }
}
