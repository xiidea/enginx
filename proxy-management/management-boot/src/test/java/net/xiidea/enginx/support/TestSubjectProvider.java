package net.xiidea.enginx.support;

import net.xiidea.enginx.application.shared.SubjectProvider;
import net.xiidea.enginx.domain.permission.AuthenticatedSubject;
import net.xiidea.enginx.domain.permission.GlobalRole;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Set;

/**
 * Lets a test say who is calling.
 *
 * <p>This substitutes at the same port the production code uses, so the authorization paths under
 * test are exactly the ones that run in production; only the source of the identity differs.
 */
public class TestSubjectProvider implements SubjectProvider {

    private volatile AuthenticatedSubject current =
            new AuthenticatedSubject("anonymous", Set.of(), Set.of());

    public void actAs(String subject, Set<String> groupPaths, GlobalRole... roles) {
        this.current = new AuthenticatedSubject(subject, groupPaths, Set.of(roles));
    }

    public void actAsSuperAdmin() {
        actAs("super-admin", Set.of(), GlobalRole.SUPER_ADMIN);
    }

    public void actAsNobody() {
        this.current = new AuthenticatedSubject(null, Set.of(), Set.of());
    }

    @Override
    public AuthenticatedSubject currentSubject() {
        return current;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public TestSubjectProvider testSubjectProvider() {
            return new TestSubjectProvider();
        }
    }
}
