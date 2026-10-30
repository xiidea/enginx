package net.xiidea.enginx.application.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateKeyDerivationTest {

    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) (i + 1);
        }
    }

    @Test
    @DisplayName("signs exactly as the gate does: the same vector is asserted by enginx-agent/internal/gate")
    void matchesTheGate() {
        assertThat(GateKeyDerivation.relaySignature(KEY, "aaaaaaaa-0000-0000-0000-000000000001",
                "bob@example.com", "123456", 1700000000L))
                .isEqualTo("69d6c01cb6c98268a679c774549cd8befb1411289ae913a4a8c9303ea824c77e");
    }

    @Test
    @DisplayName("signs an events section exactly as the gate does")
    void eventsMatchTheGate() {
        assertThat(GateKeyDerivation.eventsSignature(KEY, "{\"siteId\":\"aaaaaaaa-0000-0000-0000-000000000001\"}"))
                .isEqualTo("6bf9ed01167328bfa0145c307a2bfb9f7a959a54e0b4c197ed8edc1b0d90ffa7");
    }

    @Test
    void sessionAndRelayKeysDiffer() {
        assertThat(GateKeyDerivation.sessionKey(KEY)).isNotEqualTo(GateKeyDerivation.relayKey(KEY));
    }

    @Test
    void signaturesCompareExactly() {
        assertThat(GateKeyDerivation.signatureMatches("abc", "abc")).isTrue();
        assertThat(GateKeyDerivation.signatureMatches("abc", "abd")).isFalse();
        assertThat(GateKeyDerivation.signatureMatches("abc", null)).isFalse();
    }
}
