package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.enums.UserRole;
import com.github.wellch4n.oops.service.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class UserInitializer implements ApplicationRunner {

    private final UserService userService;

    public UserInitializer(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userService.hasAdmin()) {
            userService.createUser("admin", null, "admin123", UserRole.ADMIN);
        }
    }
}
