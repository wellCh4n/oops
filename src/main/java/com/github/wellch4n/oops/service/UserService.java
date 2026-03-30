package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.User;
import com.github.wellch4n.oops.data.UserRepository;
import com.github.wellch4n.oops.enums.UserRole;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

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
}
