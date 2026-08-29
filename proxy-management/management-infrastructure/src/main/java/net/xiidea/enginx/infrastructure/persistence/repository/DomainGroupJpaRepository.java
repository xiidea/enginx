package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.DomainGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DomainGroupJpaRepository extends JpaRepository<DomainGroupEntity, UUID> {

    boolean existsByPath(String path);

    boolean existsByParentId(UUID parentId);

    List<DomainGroupEntity> findByIdIn(Collection<UUID> ids);

    List<DomainGroupEntity> findByPathIn(Collection<String> paths);

    /**
     * Every group at or beneath the given paths.
     *
     * <p>The {@code like} runs against a {@code text_pattern_ops} index, so widening a grant
     * down the tree stays an index range scan rather than a full table read. The prefixes
     * supplied by the caller always end in a dot, which is what stops {@code production} from
     * matching {@code production-legacy}.
     */
    @Query("""
            select g from DomainGroupEntity g
            where g.id in :ids
               or exists (
                    select 1 from DomainGroupEntity a
                    where a.id in :ids and g.path like concat(a.path, '.%'))
            """)
    List<DomainGroupEntity> findSelfAndDescendants(@Param("ids") Collection<UUID> ids);
}
