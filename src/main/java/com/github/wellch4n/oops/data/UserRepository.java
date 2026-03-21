package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByRole(UserRole role);
}
