package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;

import java.util.HashMap;
import java.util.Map;

/** Flattens a site into the before/after payload stored on an audit event. */
final class ProxySiteSnapshot {

    private ProxySiteSnapshot() {
    }

    static Map<String, Object> of(ProxySite site) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", site.id().toString());
        map.put("name", site.name());
        map.put("domain", site.domain().value());
        map.put("nginxInstanceId", site.nginxInstanceId().toString());
        map.put("adminState", site.adminState().name());
        map.put("status", site.status().name());
        map.put("activeFrom", String.valueOf(site.window().activeFrom()));
        map.put("expiresAt", String.valueOf(site.window().expiresAt()));
        map.put("sslEnabled", site.spec().sslEnabled());
        map.put("loadBalancingMethod", site.spec().loadBalancingMethod().name());
        map.put("upstreams", site.spec().upstreams().stream()
                .map(u -> u.scheme() + "://" + u.authority())
                .toList());
        map.put("upstreamCount", site.spec().upstreams().size());
        // Who may sign in, never how: hashes stay out of the audit trail.
        map.put("protectionMode", site.spec().protection().mode().name());
        map.put("basicAuthUsers", site.spec().protection().basicUsers().stream()
                .map(net.xiidea.enginx.domain.proxy.BasicAuthUser::username)
                .toList());
        net.xiidea.enginx.domain.proxy.EmailOtpPolicy emailOtp = site.spec().protection().emailOtp();
        if (emailOtp != null) {
            map.put("allowedEmails", emailOtp.allowedEmails().stream()
                    .map(net.xiidea.enginx.domain.proxy.AllowedEmail::value)
                    .toList());
            map.put("sessionTtlSeconds", emailOtp.sessionTtl().toSeconds());
        }
        return Map.copyOf(map);
    }

    static String describe(UpstreamTarget upstream) {
        return upstream.scheme() + "://" + upstream.authority();
    }
}
