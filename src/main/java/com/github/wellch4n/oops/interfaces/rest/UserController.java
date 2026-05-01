package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.CreateUserRequest;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.interfaces.dto.UpdateUserRequest;
import com.github.wellch4n.oops.application.service.UserService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<User>> listUsers() {
        return Result.success(userService.listUsers());
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Result<User> me(Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return userService.findById(principal.userId())
                .map(Result::success)
                .orElse(Result.failure("User not found"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> createUser(@RequestBody CreateUserRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            return Result.failure("Username is required");
        }
        if (request.email() == null || request.email().isBlank()) {
            return Result.failure("Email is required");
        }
        userService.createUser(request.username(), request.email(), request.password(), UserRole.USER);
        return Result.success(true);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> updateUser(@PathVariable String id, @RequestBody UpdateUserRequest request) {
        userService.updateUser(id, request.role(), request.email(), request.password());
        return Result.success(true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return Result.success(true);
    }
}
