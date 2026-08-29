package net.xiidea.enginx.config;

import net.xiidea.enginx.domain.deployment.CertificateMaterialProvider;
import net.xiidea.enginx.domain.deployment.NginxConfigRenderer;
import net.xiidea.enginx.domain.deployment.RenderedCertificate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

/**
 * Deployment collaborators that are plain objects rather than components.
 *
 * <p>The outbox is drained by a Quartz trigger defined in the scheduler module, not by a
 * {@code @Scheduled} method here. The work is identical; scheduling it through the job store is
 * what makes the trigger itself survive a restart.
 */
@Configuration
public class DeploymentConfiguration {

    /** Plain domain logic with no dependencies, so it is constructed rather than annotated. */
    @Bean
    NginxConfigRenderer nginxConfigRenderer() {
        return new NginxConfigRenderer();
    }

    /**
     * Until Phase 6 issues and stores certificates, there is no material to hand the renderer.
     *
     * <p>Returning nothing is deliberate: a site with SSL enabled then fails to render with a
     * clear message, rather than producing a server block pointing at PEM files that do not
     * exist, which NGINX would reject at load time on the host.
     */
    @Bean
    @ConditionalOnMissingBean(CertificateMaterialProvider.class)
    CertificateMaterialProvider noCertificateMaterialYet() {
        return new CertificateMaterialProvider() {
            @Override
            public Optional<RenderedCertificate> materialFor(UUID certificateId) {
                return Optional.empty();
            }
        };
    }
}
