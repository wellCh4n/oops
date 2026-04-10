package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.User;
import com.github.wellch4n.oops.objects.LoginRequest;
import com.github.wellch4n.oops.objects.LoginResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.UserService;
import com.github.wellch4n.oops.utils.JwtUtils;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtils jwtUtils;

    public AuthController(UserService userService, JwtUtils jwtUtils) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = userService.findByUsernameOrEmail(request.username());
        if (userOpt.isEmpty() || !userService.checkPassword(userOpt.get(), request.password())) {
            return Result.failure("用户名或密码错误");
        }
        User user = userOpt.get();
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        return Result.success(new LoginResponse(token, user.getId(), user.getUsername(), user.getRole()));
    }
}
