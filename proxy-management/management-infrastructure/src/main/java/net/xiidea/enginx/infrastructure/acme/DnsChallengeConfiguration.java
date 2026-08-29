package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.DnsChallengePublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a DNS challenge publisher when no provider is configured.
 *
 * <p>A {@code @Bean} rather than a {@code @Component} carrying {@code @ConditionalOnMissingBean}:
 * that condition is evaluated while components are being scanned, when whether a real provider
 * exists is not yet decided, so the null object would sometimes win and sometimes lose depending
 * on scan order. Configuration classes are processed afterwards, where the question has an answer.
 */
@Configuration(proxyBeanMethods = false)
public class DnsChallengeConfiguration {

    /**
     * A null object rather than an absent bean, so a deployment that never wants wildcards does
     * not have to configure a DNS provider merely to start. The failure then happens at the moment
     * a wildcard is requested, with a message that says what to do.
     */
    @Bean
    @ConditionalOnMissingBean(DnsChallengePublisher.class)
    DnsChallengePublisher unconfiguredDnsChallengePublisher() {
        return new DnsChallengePublisher() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public void publish(String recordName, String value) {
                throw new CertificateIssuanceException(
                        "DNS-01 validation is required for this certificate, but no DNS provider is "
                                + "configured. Configure one under enginx.acme.dns, or list the subdomains "
                                + "explicitly instead of using a wildcard.", false);
            }

            @Override
            public void withdraw(String recordName) {
                // Nothing was ever published.
            }
        };
    }
}
