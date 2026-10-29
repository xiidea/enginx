package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.regex.Pattern;

/**
 * The platform's identity provider, as the gates use it to sign visitors in.
 *
 * <p>One for the whole platform; each site only decides who among its users may enter. Every value
 * is rendered into gated sites' files, so each is held to a pattern with no quotes, backslashes or
 * whitespace.
 *
 * @param issuer        must match the {@code iss} of every token; also where the browser is sent
 * @param backchannel   the provider's origin as hosts reach it, when that differs from the issuer's;
 *                      the gate fetches discovery, keys and tokens there. Null when the same
 * @param clientSecret  null for a public client, which is the recommended setup: PKCE protects the
 *                      code exchange, and no secret has to be copied to every gated host
 */
public record GateOidcProvider(String issuer, String backchannel, String clientId, String clientSecret, String scopes) {

    private static final Pattern URL =
            Pattern.compile("^https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?$");
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9._@/:-]{1,128}$");
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9._~+/=-]{1,256}$");
    private static final Pattern SCOPES = Pattern.compile("^[A-Za-z0-9_.:/-]+( [A-Za-z0-9_.:/-]+)*$");

    public GateOidcProvider {
        if (issuer == null || !URL.matcher(issuer).matches()) {
            throw new ValidationException("enginx.gate.oidc.issuer-uri", "The gate's OIDC issuer must be a plain http(s) URL");
        }
        backchannel = backchannel == null || backchannel.isBlank() ? null : backchannel.trim();
        if (backchannel != null && !URL.matcher(backchannel).matches()) {
            throw new ValidationException("enginx.gate.oidc.backchannel-uri",
                    "The gate's OIDC backchannel must be a plain http(s) URL");
        }
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new ValidationException("enginx.gate.oidc.client-id", "The gate's OIDC client id is missing or invalid");
        }
        clientSecret = clientSecret == null || clientSecret.isBlank() ? null : clientSecret.trim();
        if (clientSecret != null && !SECRET.matcher(clientSecret).matches()) {
            throw new ValidationException("enginx.gate.oidc.client-secret",
                    "The gate's OIDC client secret contains characters it cannot carry");
        }
        scopes = scopes == null || scopes.isBlank() ? "openid email profile" : scopes.trim();
        if (!SCOPES.matcher(scopes).matches() || !(" " + scopes + " ").contains(" openid ")) {
            throw new ValidationException("enginx.gate.oidc.scopes", "The gate's OIDC scopes must include openid");
        }
    }

    @Override
    public String toString() {
        return "GateOidcProvider[issuer=" + issuer + ", clientId=" + clientId + ", clientSecret="
                + (clientSecret == null ? "none" : "<redacted>") + "]";
    }
}
