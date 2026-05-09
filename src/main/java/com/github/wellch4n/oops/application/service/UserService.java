package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final String ACCESS_TOKEN_PREFIX = "sk-oops-";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findById(String id) {
        return userRepository.findById(id);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Map<String, String> getUsernameMapByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (left, right) -> left));
    }

    public Optional<User> findByUsernameOrEmail(String identifier) {
        Optional<User> user = userRepository.findByUsername(identifier);
        if (user.isPresent()) return user;
        return userRepository.findByEmail(identifier);
    }

    public boolean checkPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPassword());
    }

    public User createUser(String username, String email, String rawPassword, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        if (rawPassword != null && !rawPassword.isBlank()) {
            user.setPassword(passwordEncoder.encode(rawPassword));
        }
        user.setRole(role);
        return userRepository.save(user);
    }

    public List<User> listUsers() {
        return userRepository.findAll();
    }

    public void deleteUser(String id) {
        userRepository.deleteById(id);
    }

    public void updateMyProfile(String userId, String email) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("User not found"));
        user.setEmail(email == null || email.isBlank() ? null : email.trim());
        userRepository.save(user);
    }

    public void changeMyPassword(String userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException("New password is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("User not found"));
        if (!checkPassword(user, oldPassword)) {
            throw new BizException("Old password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void updateUser(String id, UserRole role, String email, String rawPassword) {
        userRepository.findById(id).ifPresent(user -> {
            user.setRole(role);
            user.setEmail(email);
            if (rawPassword != null && !rawPassword.isBlank()) {
                user.setPassword(passwordEncoder.encode(rawPassword));
            }
            userRepository.save(user);
        });
    }

    public boolean hasAdmin() {
        return userRepository.existsByRole(UserRole.ADMIN);
    }

    public String resetMyAccessToken(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("User not found"));
        String token = ACCESS_TOKEN_PREFIX + NanoIdUtils.generate();
        user.setAccessToken(token);
        userRepository.save(user);
        return token;
    }
}
