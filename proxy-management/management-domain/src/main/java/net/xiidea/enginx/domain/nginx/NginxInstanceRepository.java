package net.xiidea.enginx.domain.nginx;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NginxInstanceRepository {

    NginxInstance save(NginxInstance instance);

    Optional<NginxInstance> findById(UUID id);

    boolean existsByName(String name);

    List<NginxInstance> findAll();
}
