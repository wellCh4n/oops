package com.github.wellch4n.oops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OopsApplication.class, args);
    }

}
