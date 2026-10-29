package net.xiidea.enginx.application.gate;

import java.time.Duration;

/** Delivers a sign-in code. SMTP stays on the platform, so no host ever holds mail credentials. */
public interface OtpMailer {

    /** Whether delivery is configured at all. */
    boolean available();

    void sendCode(String to, String domain, String code, Duration validFor);
}
