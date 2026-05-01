package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findByUsername(String username);

    Optional<User> findById(String id);

    Optional<User> findByEmail(String email);

    List<User> findAllById(Collection<String> ids);

    Optional<User> findByUsernameOrEmail(String identifier);

    User save(User user);

    List<User> findAll();

    void deleteById(String id);

    boolean existsByRole(UserRole role);
}
