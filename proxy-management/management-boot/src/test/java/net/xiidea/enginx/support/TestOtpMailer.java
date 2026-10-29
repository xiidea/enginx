package net.xiidea.enginx.support;

import net.xiidea.enginx.application.gate.OtpMailer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Records sign-in codes instead of mailing them, and can play a platform with no mail server. */
public class TestOtpMailer implements OtpMailer {

    public record Sent(String to, String domain, String code) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private volatile boolean available = true;

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void available(boolean value) {
        this.available = value;
    }

    public synchronized void reset() {
        sent.clear();
        available = true;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public synchronized void sendCode(String to, String domain, String code, Duration validFor) {
        sent.add(new Sent(to, domain, code));
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public TestOtpMailer testOtpMailer() {
            return new TestOtpMailer();
        }
    }
}
