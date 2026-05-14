package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.application.port.repository.PageResult;
import com.github.wellch4n.oops.domain.shared.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class UserPersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.UserRepository {
    private final UserRepository userRepository;

    public UserPersistenceAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.User> findByUsername(String username) {
        return userRepository.findByUsername(username).map(PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.User> findById(String id) {
        return userRepository.findById(id).map(PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.User> findByEmail(String email) {
        return userRepository.findByEmail(email).map(PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.User> findByAccessToken(String accessToken) {
        return userRepository.findByAccessToken(accessToken).map(PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.identity.User> findAllById(Collection<String> ids) {
        return PersistenceMapper.convertList(userRepository.findAllById(ids), PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.User> findByUsernameOrEmail(String identifier) {
        Optional<com.github.wellch4n.oops.domain.identity.User> user = findByUsername(identifier);
        if (user.isPresent()) {
            return user;
        }
        return findByEmail(identifier);
    }

    @Override
    public com.github.wellch4n.oops.domain.identity.User save(com.github.wellch4n.oops.domain.identity.User user) {
        return PersistenceMapper.toDomain(userRepository.save(PersistenceMapper.toEntity(user)));
    }

    @Override
    public List<com.github.wellch4n.oops.domain.identity.User> findAll() {
        return PersistenceMapper.convertList(userRepository.findAll(), PersistenceMapper::toDomain);
    }

    @Override
    public PageResult<com.github.wellch4n.oops.domain.identity.User> findPage(String keyword, int page, int size) {
        var result = userRepository.searchPage(
                keyword == null ? "" : keyword,
                PageRequest.of(Math.max(page - 1, 0), size));
        return new PageResult<>(
                result.getTotalElements(),
                PersistenceMapper.convertList(result.getContent(), PersistenceMapper::toDomain),
                result.getSize(),
                result.getTotalPages()
        );
    }

    @Override
    public void deleteById(String id) {
        userRepository.deleteById(id);
    }

    @Override
    public boolean existsByRole(UserRole role) {
        return userRepository.existsByRole(role);
    }
}
