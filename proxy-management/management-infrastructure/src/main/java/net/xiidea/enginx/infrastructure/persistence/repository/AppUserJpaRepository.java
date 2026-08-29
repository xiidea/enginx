package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AppUserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByKeycloakSubject(String keycloakSubject);

    void deleteByKeycloakSubject(String keycloakSubject);

    List<AppUserEntity> findByKeycloakSubjectIn(Collection<String> subjects);

    /** Most recently seen first: one username can belong to more than one subject. */
    List<AppUserEntity> findByUsernameIgnoreCaseOrderByLastLoginAtDesc(String username);

    /**
     * Users whose username or display name contains the pattern.
     *
     * <p>The pattern arrives already lowercased and wrapped in wildcards, so a blank search is
     * just {@code %} and needs no separate query. {@code escape} matters because the search text
     * is typed by a person: without it a stray {@code %} silently becomes a wildcard and an
     * underscore matches any character.
     *
     * <p>The escape character is {@code !} rather than the conventional backslash, because a
     * backslash inside a JPQL string literal is itself ambiguous -- it has to survive the Java
     * source, the JPQL parser and the dialect, and the failure is silent: the escape stops
     * working and the search quietly returns everyone.
     */
    @Query("""
            select u from AppUserEntity u
            where lower(u.username) like :pattern escape '!'
               or lower(coalesce(u.displayName, '')) like :pattern escape '!'
            """)
    Page<AppUserEntity> search(@Param("pattern") String pattern, Pageable pageable);

    /**
     * The same search, restricted to entries that are stale.
     *
     * <p>Stale means one of two things, and only the first is certain: a {@code local:} subject
     * with no matching account, or -- when {@code dormantBefore} is given -- one last seen before
     * then. A federated subject is never stale on the first ground, because this platform cannot
     * ask the provider whether the account still exists.
     *
     * <p>Native because the first condition is a join against a table this entity has no relation
     * to, and expressing it in JPQL would mean modelling a relationship that exists only for this
     * one query.
     */
    @Query(value = """
            select * from app_users u
            where (lower(u.username) like :pattern escape '!'
                   or lower(coalesce(u.display_name, '')) like :pattern escape '!')
              and (
                    (u.keycloak_subject like 'local:%'
                     and not exists (
                         select 1 from local_users l
                         where 'local:' || l.id::text = u.keycloak_subject))
                 or (cast(:dormantBefore as timestamptz) is not null
                     and u.last_login_at < cast(:dormantBefore as timestamptz))
              )
            order by u.username
            """,
            countQuery = """
            select count(*) from app_users u
            where (lower(u.username) like :pattern escape '!'
                   or lower(coalesce(u.display_name, '')) like :pattern escape '!')
              and (
                    (u.keycloak_subject like 'local:%'
                     and not exists (
                         select 1 from local_users l
                         where 'local:' || l.id::text = u.keycloak_subject))
                 or (cast(:dormantBefore as timestamptz) is not null
                     and u.last_login_at < cast(:dormantBefore as timestamptz))
              )
            """,
            nativeQuery = true)
    Page<AppUserEntity> searchStale(@Param("pattern") String pattern,
                                    @Param("dormantBefore") Instant dormantBefore,
                                    Pageable pageable);

    void deleteByKeycloakSubjectIn(Collection<String> subjects);
}
