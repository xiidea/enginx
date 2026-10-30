package net.xiidea.enginx.api.agent;

import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.SiteVerification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a pull host reports from a probe is read against what it was asked, never on its word. */
class VerifyResultMappingTest {

    private static final List<NginxAgentPort.VerifyTarget> TARGETS = List.of(
            new NginxAgentPort.VerifyTarget("app.example.com", "enginx v1 abc", "BASIC"));

    @Test
    @DisplayName("the expected marker and protection come from the job's targets, not the host")
    void expectationsComeFromTheTargets() {
        AgentJobController.ResultRequest request = new AgentJobController.ResultRequest(true, null, null, null,
                null, null, null, null, List.of(
                new AgentJobController.VerificationResult("app.example.com", true, 401, true, "enginx v1 abc",
                        null, true, null),
                // A name the job never asked about is not reported as verified.
                new AgentJobController.VerificationResult("other.example.com", true, 200, true, "x", null, null, null)));

        AgentJobResult result = request.toDomain(TARGETS);

        assertThat(result.verifications()).singleElement().satisfies(site -> {
            assertThat(site.serverName()).isEqualTo("app.example.com");
            assertThat(site.expectedMarker()).isEqualTo("enginx v1 abc");
            assertThat(site.expectedProtection()).isEqualTo("BASIC");
            assertThat(site.protectionEnforced()).isTrue();
        });
    }

    @Test
    @DisplayName("a result for any other job carries no verifications")
    void otherJobsCarryNone() {
        AgentJobController.ResultRequest request = new AgentJobController.ResultRequest(true, null, "ok", "1.27.5",
                null, null, null, null, null);
        assertThat(request.toDomain(null).verifications()).isNull();
        assertThat(new SiteVerification("a", true, 200, true, "m", "m", null).served()).isTrue();
    }
}
