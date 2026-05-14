package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.UserRole;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByAccessToken(String accessToken);
    boolean existsByRole(UserRole role);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "ORDER BY u.createdTime DESC")
    Page<User> searchPage(@Param("keyword") String keyword, Pageable pageable);
}
