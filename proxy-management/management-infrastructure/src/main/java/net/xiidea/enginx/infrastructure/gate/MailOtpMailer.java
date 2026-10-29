package net.xiidea.enginx.infrastructure.gate;

import net.xiidea.enginx.application.gate.OtpMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Sign-in codes by the platform's own SMTP, the same configuration notifications use.
 *
 * <p>Always registered, so the relay can say "mail is not configured" rather than fail to start;
 * the sender exists only when {@code spring.mail.host} is set.
 */
@Component
public class MailOtpMailer implements OtpMailer {

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public MailOtpMailer(ObjectProvider<JavaMailSender> sender,
                         @Value("${enginx.gate.mail-from:${enginx.notifications.from:enginx@localhost}}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public boolean available() {
        return sender.getIfAvailable() != null;
    }

    @Override
    public void sendCode(String to, String domain, String code, Duration validFor) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        // The code is in the body, never the subject: subjects are what lock screens and
        // notification previews show to whoever is near the phone.
        message.setSubject("Your sign-in code for " + domain);
        message.setText("""
                Your code to sign in to %s is:

                    %s

                It works once, for %d minutes. If you did not ask for it, ignore this email: \
                nobody can sign in without the code.

                -- \nEasy NGINX Admin""".formatted(domain, code, validFor.toMinutes()));
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            throw new IllegalStateException("Mail is not configured");
        }
        mail.send(message);
    }
}
