package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static net.xiidea.enginx.domain.deployment.RenderFixtures.BUNDLE;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.INSTANCE;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.NOW;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.NO_CERTIFICATES;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.TEST_CERTIFICATE;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.location;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.requestHeader;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.responseHeader;
import static net.xiidea.enginx.domain.deployment.RenderFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-file tests for the configuration renderer.
 *
 * <p>Checked-in expected output is the only practical way to notice that a renderer change
 * altered a directive somewhere unrelated. Without it, a regression stays invisible until a
 * deployment fails validation, or worse, passes validation and routes traffic somewhere new.
 *
 * <p>To update after an intended change: run with {@code -Dgolden.update=true} and read the diff
 * before committing it.
 */
class NginxConfigRendererTest {

    /** A current NGINX that has the platform supply its catch-all. */
    private static final RenderTarget MODERN = new RenderTarget("1.27.5", true);

    private static final String SITE_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private final NginxConfigRenderer renderer = new NginxConfigRenderer();

    @Nested
    @DisplayName("golden output")
    class Golden {

        @Test
        void simpleHttpSite() {
            ProxySite site = spec("app.example.com").site(SITE_ID);
            assertMatchesGolden("simple-http.conf", renderer.renderSitePreview(site, NO_CERTIFICATES, MODERN));
        }

        @Test
        void httpsWithRedirectAndHsts() {
            ProxySite site = spec("secure.example.com").ssl(true, true).site(SITE_ID);
            assertMatchesGolden("https-redirect-hsts.conf", renderer.renderSitePreview(site, TEST_CERTIFICATE, MODERN));
        }

        @Test
        void httpsWithoutRedirectServesBothPorts() {
            // Turning the redirect off means HTTP is served too, not that port 80 falls through to
            // the catch-all. The plain server keeps the HTTP-01 location so renewals still work.
            ProxySite site = spec("both.example.com").ssl(false, false).site(SITE_ID);
            assertMatchesGolden("https-and-http.conf", renderer.renderSitePreview(site, TEST_CERTIFICATE, MODERN));
        }

        @Test
        void loadBalancedWithBackupAndHealthChecks() {
            ProxySite site = spec("lb.example.com")
                    .loadBalancing(LoadBalancingMethod.LEAST_CONN)
                    .upstreams(
                            new UpstreamTarget("http", "10.0.0.1", 8080, 5, 2, 15, false),
                            new UpstreamTarget("http", "10.0.0.2", 8080, 1, 3, 10, false),
                            new UpstreamTarget("http", "10.0.0.9", 8080, 1, 3, 10, true))
                    .site(SITE_ID);
            assertMatchesGolden("load-balanced.conf", renderer.renderSitePreview(site, NO_CERTIFICATES, MODERN));
        }

        @Test
        void websocketAndCustomHeadersAndLocations() {
            ProxySite site = spec("ws.example.com")
                    .websocket()
                    .headers(requestHeader("X-Tenant-Id", "acme"), responseHeader("X-Frame-Options", "DENY"))
                    .locations(location("/api", LocationMatchType.PREFIX, 0),
                            location("/health", LocationMatchType.EXACT, 1))
                    .timeouts(new ProxyTimeouts(5, 120, 30, 52_428_800L))
                    .site(SITE_ID);
            assertMatchesGolden("websocket-headers.conf", renderer.renderSitePreview(site, NO_CERTIFICATES, MODERN));
        }
    }

    @Nested
    @DisplayName("bundle assembly")
    class Bundle {

        @Test
        @DisplayName("the same sites always produce the same hash, whatever order they arrive in")
        void renderingIsDeterministic() {
            List<ProxySite> forwards = List.of(
                    spec("a.example.com").site("aaaaaaaa-0000-0000-0000-00000000000a"),
                    spec("b.example.com").site("aaaaaaaa-0000-0000-0000-00000000000b"));

            ConfigBundle first = renderer.render(BUNDLE, INSTANCE, MODERN, 1, forwards, NO_CERTIFICATES, "ada", NOW);
            ConfigBundle second = renderer.render(UUID.randomUUID(), INSTANCE, MODERN, 2,
                    forwards.reversed(), NO_CERTIFICATES, "ada", NOW);

            assertThat(second.contentHash()).isEqualTo(first.contentHash());
            assertThat(first.hasSameContentAs(second)).isTrue();
        }

        @Test
        @DisplayName("a changed upstream changes the hash, which is what makes a redeploy detectable")
        void contentChangesTheHash() {
            ConfigBundle before = renderer.render(BUNDLE, INSTANCE, MODERN, 1,
                    List.of(spec("a.example.com").site(SITE_ID)), NO_CERTIFICATES, "ada", NOW);
            ConfigBundle after = renderer.render(BUNDLE, INSTANCE, MODERN, 2,
                    List.of(spec("a.example.com").upstreams(UpstreamTarget.of("http", "10.0.0.99", 9090))
                            .site(SITE_ID)), NO_CERTIFICATES, "ada", NOW);

            assertThat(after.contentHash()).isNotEqualTo(before.contentHash());
        }

        @Test
        void bundleCarriesTheSharedMapAndOneFilePerSite() {
            ConfigBundle bundle = renderer.render(BUNDLE, INSTANCE, MODERN, 1,
                    List.of(spec("a.example.com").site("aaaaaaaa-0000-0000-0000-00000000000a"),
                            spec("b.example.com").site("aaaaaaaa-0000-0000-0000-00000000000b")),
                    NO_CERTIFICATES, "ada", NOW);

            assertThat(bundle.files()).extracting(BundleFile::path)
                    .containsExactly("conf.d/00-enginx-base.conf",
                            "conf.d/a.example.com.conf",
                            "conf.d/b.example.com.conf");
            assertThat(bundle.siteIds()).hasSize(2);
        }

        @Test
        @DisplayName("a catch-all server answers unmatched hosts, so an expired domain stops resolving")
        void bundleDeclaresADefaultServer() {
            ConfigBundle bundle = renderer.render(BUNDLE, INSTANCE, MODERN, 1,
                    List.of(spec("a.example.com").site(SITE_ID)), NO_CERTIFICATES, "ada", NOW);

            String base = bundle.files().stream()
                    .filter(file -> file.path().equals("conf.d/00-enginx-base.conf"))
                    .findFirst().orElseThrow().content();

            assertThat(base).contains("listen 80 default_server;");
            assertThat(base).contains("return 404;");

            // The same guarantee on 443, which needs a certificate to exist at all. Without it an
            // unserved name reached over HTTPS is answered by whichever site loaded first.
            assertThat(base).contains("listen 443 ssl default_server;");
            assertThat(base).contains("/etc/nginx/enginx/default-tls/default.crt");

            // One default per listen port, which is NGINX's actual rule: a second on the same
            // port is a startup error, one per port is required for the guarantee to hold on both.
            assertThat(base.split("listen 80 default_server", -1)).hasSize(2);
            assertThat(base.split("listen 443 ssl default_server", -1)).hasSize(2);
        }

        @Test
        @DisplayName("private key material is marked sensitive and excluded from the readable file list")
        void privateKeysAreMarkedSensitive() {
            ConfigBundle bundle = renderer.render(BUNDLE, INSTANCE, MODERN, 1,
                    List.of(spec("secure.example.com").ssl(true, false).site(SITE_ID)),
                    TEST_CERTIFICATE, "ada", NOW);

            assertThat(bundle.files()).filteredOn(BundleFile::sensitive)
                    .extracting(BundleFile::path)
                    .containsExactly("certs/secure.example.com/privkey.pem");
            assertThat(bundle.publicFiles()).extracting(BundleFile::path)
                    .doesNotContain("certs/secure.example.com/privkey.pem");
        }

        @Test
        @DisplayName("two sites claiming the same domain are refused rather than silently shadowed")
        void duplicateDomainsAreRefused() {
            List<ProxySite> clashing = List.of(
                    spec("same.example.com").site("aaaaaaaa-0000-0000-0000-00000000000a"),
                    spec("same.example.com").site("aaaaaaaa-0000-0000-0000-00000000000b"));

            assertThatThrownBy(() -> renderer.render(BUNDLE, INSTANCE, MODERN, 1, clashing, NO_CERTIFICATES, "ada", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("both serve same.example.com");
        }

        @Test
        @DisplayName("SSL without certificate material fails the render instead of emitting a config NGINX cannot load")
        void missingCertificateFailsTheRender() {
            assertThatThrownBy(() -> renderer.render(BUNDLE, INSTANCE, MODERN, 1,
                    List.of(spec("secure.example.com").ssl(false, false).site(SITE_ID)),
                    NO_CERTIFICATES, "ada", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("No certificate material");
        }
    }

    @Nested
    @DisplayName("upstream naming")
    class UpstreamNaming {

        @Test
        @DisplayName("domains that flatten to the same identifier still get distinct upstream names")
        void namesDoNotCollide() {
            // a-b.com and a.b.com both flatten to a_b_com; NGINX would reject the duplicate.
            String first = NginxConfigRenderer.upstreamName(spec("a-b.example.com").site(SITE_ID));
            String second = NginxConfigRenderer.upstreamName(spec("a.b.example.com").site(SITE_ID));

            assertThat(first).isNotEqualTo(second);
            assertThat(first).matches("^[a-z0-9_]+$");
            assertThat(second).matches("^[a-z0-9_]+$");
        }
    }

    // ---- golden file helper ------------------------------------------------

    private static void assertMatchesGolden(String name, String actual) {
        if (Boolean.getBoolean("golden.update")) {
            writeGolden(name, actual);
            return;
        }
        String expected = readGolden(name);
        assertThat(actual)
                .describedAs("Rendered output differs from golden/%s. If the change is intended, "
                        + "re-run with -Dgolden.update=true and review the diff.", name)
                .isEqualTo(expected);
    }

    private static String fileContent(ConfigBundle bundle, String path) {
        return bundle.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst().orElseThrow().content();
    }

    private static String readGolden(String name) {
        try (InputStream in = NginxConfigRendererTest.class.getResourceAsStream("/golden/" + name)) {
            if (in == null) {
                throw new AssertionError("Missing golden file golden/" + name
                        + ". Create it with -Dgolden.update=true.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not read golden/" + name, e);
        }
    }

    private static void writeGolden(String name, String content) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("src/test/resources/golden", name);
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not write golden/" + name, e);
        }
    }

    @Nested
    @DisplayName("hosts that already ran NGINX")
    class SharedHosts {

        @Test
        void anNginxOlderThan1251GetsTheListenParameterForm() {
            ProxySite site = spec("old.example.com").ssl(true, false).site(SITE_ID);
            String rendered = renderer.renderSitePreview(site, TEST_CERTIFICATE, new RenderTarget("1.22.1", true));

            // `http2 on;` arrived in 1.25.1; on 1.22 it fails nginx -t, which is every stock
            // NGINX on current LTS distributions.
            assertThat(rendered).contains("listen 443 ssl http2;").doesNotContain("http2 on;");
        }

        @Test
        void anUnknownVersionGetsTheFormEveryVersionAccepts() {
            ConfigBundle bundle = renderer.render(BUNDLE, INSTANCE, RenderTarget.UNKNOWN, 1,
                    List.of(spec("x.example.com").site(SITE_ID)), NO_CERTIFICATES, "ada", NOW);
            assertThat(fileContent(bundle, "conf.d/00-enginx-base.conf"))
                    .contains("listen 443 ssl http2 default_server;").doesNotContain("http2 on;");
        }

        @Test
        void aHostWithItsOwnDefaultServerGetsNoCatchAll() {
            ConfigBundle bundle = renderer.render(BUNDLE, INSTANCE, new RenderTarget("1.22.1", false), 1,
                    List.of(spec("x.example.com").site(SITE_ID)), NO_CERTIFICATES, "ada", NOW);
            String base = fileContent(bundle, "conf.d/00-enginx-base.conf");

            assertThat(base).doesNotContain("default_server").contains("map $http_upgrade");
        }

        @Test
        void everyPort80ServerAnswersTheMarkerWithTheSiteId() {
            ProxySite redirect = spec("r.example.com").ssl(true, true).site(SITE_ID);
            ProxySite plain = spec("p.example.com").site(SITE_ID);
            String marker = "location = " + NginxConfigRenderer.SITE_MARKER_PATH;

            for (ProxySite site : List.of(redirect, plain)) {
                String rendered = renderer.renderSitePreview(site, TEST_CERTIFICATE, MODERN);
                assertThat(rendered).contains(marker).contains("return 200 \"" + SITE_ID + "\\n\";");
            }
        }
    }
}
