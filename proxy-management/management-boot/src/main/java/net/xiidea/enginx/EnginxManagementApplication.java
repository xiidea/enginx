package net.xiidea.enginx;

import net.xiidea.enginx.domain.permission.PermissionEvaluationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Entry point.
 *
 * <p>This class sits in {@code net.xiidea.enginx} deliberately: Boot derives its component scan, entity
 * scan and repository scan from the package of the annotated class, so every module underneath
 * is discovered without any explicit scan configuration to drift out of date.
 */
@SpringBootApplication
public class EnginxManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnginxManagementApplication.class, args);
    }

    /**
     * Time is injected, never read from a static. Every lifecycle rule in this system is a
     * function of "now", and the expiration tests in Phase 5 depend on being able to move it.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The permission evaluator is plain domain logic with no dependencies of its own, so it is
     * constructed here rather than annotated. Keeping the annotation out of the domain module is
     * what lets the whole authorization rule set be unit-tested without a Spring context.
     */
    @Bean
    PermissionEvaluationService permissionEvaluationService() {
        return new PermissionEvaluationService();
    }
}
