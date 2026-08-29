package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.infrastructure.persistence.entity.NginxInstanceEntity;
import net.xiidea.enginx.infrastructure.persistence.mapper.NginxInstanceMapper;
import net.xiidea.enginx.infrastructure.persistence.repository.NginxInstanceJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NginxInstanceRepositoryAdapter implements NginxInstanceRepository {

    private final NginxInstanceJpaRepository repository;
    private final NginxInstanceMapper mapper;

    public NginxInstanceRepositoryAdapter(NginxInstanceJpaRepository repository, NginxInstanceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public NginxInstance save(NginxInstance instance) {
        NginxInstanceEntity entity = repository.findById(instance.id())
                .orElseGet(() -> new NginxInstanceEntity(instance.id()));
        mapper.applyToEntity(instance, entity);
        // saveAndFlush, not save: the @Version increment happens on flush, and the value we
        // map back becomes the caller's ETag. Returning a stale version would make the
        // client's next If-Match fail against a change it made itself.
        return mapper.toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<NginxInstance> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return repository.existsByName(name);
    }

    @Override
    public List<NginxInstance> findAll() {
        return repository.findAll(Sort.by("name")).stream().map(mapper::toDomain).toList();
    }
}
