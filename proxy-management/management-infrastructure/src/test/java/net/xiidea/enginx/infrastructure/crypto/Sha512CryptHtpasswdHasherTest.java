package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.domain.proxy.BasicAuthUser;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Sha512CryptHtpasswdHasherTest {

    private final Sha512CryptHtpasswdHasher hasher = new Sha512CryptHtpasswdHasher();

    @Test
    @DisplayName("produces the same hash as the system crypt(), here as `openssl passwd -6`")
    void matchesOpenssl() {
        // openssl passwd -6 -salt fixturesalt0001 secret
        String expected =
                "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1";

        assertThat(Sha2Crypt.sha512Crypt("secret".getBytes(StandardCharsets.UTF_8), "$6$fixturesalt0001"))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("every hash verifies, is accepted by the domain, and is salted afresh")
    void hashesVerifyAndAreSalted() {
        String first = hasher.hash("correct horse battery");
        String second = hasher.hash("correct horse battery");

        assertThat(Sha2Crypt.sha512Crypt("correct horse battery".getBytes(StandardCharsets.UTF_8), first))
                .isEqualTo(first);
        assertThat(new BasicAuthUser("alice", first).passwordHash()).isEqualTo(first);
        assertThat(second).isNotEqualTo(first);
    }
}
