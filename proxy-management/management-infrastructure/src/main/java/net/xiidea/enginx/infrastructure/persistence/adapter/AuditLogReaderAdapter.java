package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditLogReader;
import net.xiidea.enginx.domain.audit.AuditQuery;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.infrastructure.persistence.entity.AuditLogEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AuditLogQueryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class AuditLogReaderAdapter implements AuditLogReader {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AuditLogQueryRepository repository;

    public AuditLogReaderAdapter(AuditLogQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditEvent> search(AuditQuery query) {
        Specification<AuditLogEntity> specification = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (query.actor() != null) {
                // Matched against either identifier: an operator searching for a person knows
                // their username, while a token's subject claim is what the row was written with.
                predicates.add(cb.or(
                        cb.equal(root.get("actorUsername"), query.actor()),
                        cb.equal(root.get("actorSubject"), query.actor())));
            }
            if (!query.actions().isEmpty()) {
                predicates.add(root.get("action").in(query.actions()));
            }
            if (!query.results().isEmpty()) {
                predicates.add(root.get("result").in(query.results()));
            }
            if (query.resourceType() != null) {
                predicates.add(cb.equal(root.get("resourceType"), query.resourceType()));
            }
            if (query.resourceId() != null) {
                predicates.add(cb.equal(root.get("resourceId"), query.resourceId()));
            }
            // A bounded range lets PostgreSQL prune whole monthly partitions instead of scanning
            // every one of them.
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), query.to()));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLogEntity> page = repository.findAll(specification,
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "occurredAt")));

        return new PageResult<>(page.getContent().stream().map(AuditLogReaderAdapter::toDomain).toList(),
                query.page(), query.size(), page.getTotalElements());
    }

    private static AuditEvent toDomain(AuditLogEntity entity) {
        return new AuditEvent(
                entity.getId(),
                entity.getOccurredAt(),
                entity.getActorSubject(),
                entity.getActorUsername(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                parse(entity.getBeforeState()),
                parse(entity.getAfterState()),
                entity.getIpAddress(),
                entity.getUserAgent(),
                entity.getResult(),
                entity.getErrorMessage(),
                entity.getTraceId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (RuntimeException e) {
            // A row whose payload cannot be parsed is still worth returning: the actor, action and
            // outcome are the parts that matter most, and hiding the row would hide the event.
            return Map.of("unparseable", json);
        }
    }
}
