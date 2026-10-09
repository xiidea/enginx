package net.xiidea.enginx.nginx;

import net.xiidea.enginx.application.nginx.NginxInstanceService;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.nginx.PushTransport;
import net.xiidea.enginx.domain.shared.ValidationException;
import net.xiidea.enginx.infrastructure.agent.AgentTokenSecrets;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent token at rest: never stored readable, and still usable after every way it can move.
 */
@WithMockUser(roles = "SUPER_ADMIN")
class AgentTokenIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Autowired
    private NginxInstanceService service;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private AgentTokenSecrets tokens;
    @Autowired
    private SecretEncryption encryption;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate nginx_instances cascade");
    }

    private NginxInstance registerTokenHost(String name) {
        return service.registerPush(name, name + ".internal", "http://" + name + ".internal:8080",
                PushTransport.HTTP_TOKEN, null, TOKEN, "TEST", true);
    }

    private String opened(UUID id) {
        return encryption.decrypt(instances.findById(id).orElseThrow().agentToken());
    }

    @Test
    @DisplayName("a registered token is sealed, and the row holds nothing readable")
    void registrationSealsTheToken() {
        NginxInstance instance = registerTokenHost("nginx-sealed");

        Map<String, Object> row = jdbc.queryForMap(
                "select agent_token_ciphertext, agent_token_kek_id from nginx_instances where id = ?",
                instance.id());
        assertThat(new String((byte[]) row.get("agent_token_ciphertext"))).doesNotContain(TOKEN);
        assertThat(row.get("agent_token_kek_id")).isEqualTo("test");
        assertThat(opened(instance.id())).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("rotating a token replaces the sealed value and is refused for a certificate host")
    void rotation() {
        NginxInstance instance = registerTokenHost("nginx-rotated");
        String next = "fedcba9876543210fedcba9876543210";

        service.rotateAgentToken(instance.id(), next);

        assertThat(opened(instance.id())).isEqualTo(next);
        assertThatThrownBy(() -> service.rotateAgentCertificate(instance.id(), "A".repeat(64)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("re-wrapping moves tokens to the current key without losing them")
    void rewrapKeepsTheTokenReadable() {
        NginxInstance instance = registerTokenHost("nginx-rewrapped");
        byte[] before = jdbc.queryForObject("select agent_token_wrapped_dek from nginx_instances where id = ?",
                byte[].class, instance.id());

        // A key id no row is under, so every token counts as needing to move.
        int moved = tokens.rewrap("some-retired-key");

        assertThat(moved).isEqualTo(1);
        assertThat(jdbc.queryForObject("select agent_token_wrapped_dek from nginx_instances where id = ?",
                byte[].class, instance.id())).isNotEqualTo(before);
        assertThat(opened(instance.id())).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("the schema refuses an unknown transport and a host with two identities")
    void schemaHoldsTheShape() {
        byte[] sealed = {1};
        assertThatThrownBy(() -> jdbc.update("""
                insert into nginx_instances (id, name, hostname, agent_base_url, connectivity_mode,
                    push_transport, agent_token_ciphertext, agent_token_wrapped_dek, agent_token_kek_id,
                    agent_token_iv, environment, status, created_at, updated_at, version)
                values (?, 'nginx-grpc', 'grpc.internal', 'http://grpc.internal:8080', 'PUSH',
                    'GRPC_TOKEN', ?, ?, 'test', ?, 'TEST', 'UNKNOWN', now(), now(), 0)
                """, UUID.randomUUID(), sealed, sealed, sealed))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                insert into nginx_instances (id, name, hostname, agent_base_url, agent_cert_fingerprint,
                    connectivity_mode, push_transport, agent_token_ciphertext, agent_token_wrapped_dek,
                    agent_token_kek_id, agent_token_iv, environment, status, created_at, updated_at, version)
                values (?, 'nginx-both', 'both.internal', 'https://both.internal:8443', ?, 'PUSH',
                    'MTLS', ?, ?, 'test', ?, 'TEST', 'UNKNOWN', now(), now(), 0)
                """, UUID.randomUUID(), "A".repeat(64), sealed, sealed, sealed))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Half a sealed secret is no secret at all: the parts are all present or all absent.
        assertThatThrownBy(() -> jdbc.update("""
                insert into nginx_instances (id, name, hostname, agent_base_url, connectivity_mode,
                    push_transport, agent_token_ciphertext, environment, status, created_at, updated_at,
                    version)
                values (?, 'nginx-half', 'half.internal', 'http://half.internal:8080', 'PUSH',
                    'HTTP_TOKEN', ?, 'TEST', 'UNKNOWN', now(), now(), 0)
                """, UUID.randomUUID(), sealed))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a token sent for a certificate host is refused rather than dropped")
    void aTokenForAnMtlsHostIsRefused() {
        assertThatThrownBy(() -> service.registerPush("nginx-mixed", "mixed.internal",
                "https://mixed.internal:8443", PushTransport.MTLS, "A".repeat(64), TOKEN, "TEST", true))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("takes no token");
    }
}
