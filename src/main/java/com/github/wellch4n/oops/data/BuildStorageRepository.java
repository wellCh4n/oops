package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/23
 */

@Repository
public interface BuildStorageRepository extends CrudRepository<BuildStorage, String> {

    List<BuildStorage> findAllByApplicationId(String applicationId);
}
