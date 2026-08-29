package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.proxy.ProxySiteSortField;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.shared.SortDirection;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteEntity;
import net.xiidea.enginx.infrastructure.persistence.mapper.ProxySiteMapper;
import net.xiidea.enginx.infrastructure.persistence.repository.ProxySiteJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.ProxySiteSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProxySiteRepositoryAdapter implements ProxySiteRepository {

    /**
     * The only place a sort field becomes a property path. Because the domain side is an enum,
     * an attacker-supplied sort parameter cannot reach Hibernate as an arbitrary path.
     */
    private static final Map<ProxySiteSortField, String> SORT_PROPERTIES = new EnumMap<>(Map.of(
            ProxySiteSortField.NAME, "name",
            ProxySiteSortField.DOMAIN, "domain",
            ProxySiteSortField.STATUS, "status",
            ProxySiteSortField.ACTIVE_FROM, "activeFrom",
            ProxySiteSortField.EXPIRES_AT, "expiresAt",
            ProxySiteSortField.CREATED_AT, "createdAt",
            ProxySiteSortField.UPDATED_AT, "updatedAt"));

    private final ProxySiteJpaRepository repository;
    private final ProxySiteMapper mapper;

    public ProxySiteRepositoryAdapter(ProxySiteJpaRepository repository, ProxySiteMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public ProxySite save(ProxySite site) {
        ProxySiteEntity entity = repository.findById(site.id()).orElseGet(() -> new ProxySiteEntity(site.id()));
        mapper.applyToEntity(site, entity);
        // saveAndFlush, not save: the @Version increment happens on flush, and the value we
        // map back becomes the caller's ETag. Returning a stale version would make the
        // client's next If-Match fail against a change it made itself.
        return mapper.toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<ProxySite> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public boolean existsByInstanceAndDomain(UUID nginxInstanceId, DomainName domain, UUID excludeId) {
        return repository.existsByInstanceAndDomain(nginxInstanceId, domain.value(), excludeId);
    }

    @Override
    public PageResult<ProxySite> search(ProxySiteQuery query, AccessScope scope) {
        Sort sort = Sort.by(query.sorts().stream()
                .map(s -> new Sort.Order(
                        s.direction() == SortDirection.DESC ? Sort.Direction.DESC : Sort.Direction.ASC,
                        SORT_PROPERTIES.get(s.field())))
                .toList());

        Page<ProxySiteEntity> page = repository.findAll(
                ProxySiteSpecifications.from(query, scope),
                PageRequest.of(query.page(), query.size(), sort));

        List<ProxySite> content = page.getContent().stream().map(mapper::toDomain).toList();
        return new PageResult<>(content, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public List<ProxySite> findDeployableForInstance(UUID nginxInstanceId, Instant now) {
        return repository.findDeployable(nginxInstanceId, now).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<ProxySite> claimSitesDueForLifecycle(Instant now, int limit) {
        return repository.claimDueForLifecycle(now, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<ProxySite> findWithExpiryBefore(Instant cutoff, int limit) {
        return repository.findWithExpiryBefore(cutoff, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).toList();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }
}
