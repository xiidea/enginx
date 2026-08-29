package net.xiidea.enginx.infrastructure.notification;

import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Email.
 *
 * <p>Registered only when a mail host is configured, so a deployment without SMTP does not have to
 * carry a broken channel that fails on every scan. Plain text rather than HTML: these are read on
 * phones at inconvenient hours, and the content is a fact and an instruction, not a layout.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "spring.mail.host")
public class MailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationChannel.class);

    private final JavaMailSender sender;
    private final String from;

    public MailNotificationChannel(JavaMailSender sender,
                                   @Value("${enginx.notifications.from:enginx@localhost}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public String name() {
        return "mail";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean deliver(NotificationEvent event, Collection<String> recipients) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        // BCC rather than To: the recipient list mixes an operations alias with whichever person
        // happens to own the resource, and publishing that pairing to everyone is neither useful
        // nor anybody else's business.
        message.setBcc(recipients.toArray(String[]::new));
        message.setTo(from);
        message.setSubject("[" + event.severity() + "] " + event.subject());
        message.setText(event.body() + "\n\n-- \nEasy NGINX Admin");

        try {
            sender.send(message);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not send notification mail: {}", e.toString());
            return false;
        }
    }
}
