package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.SystemConfigKeys;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * @author wellCh4n
 * @date 2025/7/6
 */

@Repository
public interface SystemConfigRepository extends CrudRepository<SystemConfig, String> {

    SystemConfig findByConfigKey(SystemConfigKeys key);
}
