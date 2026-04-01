package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.User;
import com.github.wellch4n.oops.enums.UserRole;
import com.github.wellch4n.oops.objects.CreateUserRequest;
import com.github.wellch4n.oops.objects.UpdateUserRequest;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public Result<User> me(org.springframework.security.core.Authentication authentication) {
        return userService.findByUsername(authentication.getName())
                .map(Result::success)
                .orElse(Result.failure("用户不存在"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> createUser(@RequestBody CreateUserRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            return Result.failure("用户名不能为空");
        }
        if (request.email() == null || request.email().isBlank()) {
            return Result.failure("邮箱不能为空");
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
