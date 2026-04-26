package com.github.wellch4n.oops;

import com.github.wellch4n.oops.config.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ImportRuntimeHints(NativeRuntimeHints.class)
public class OopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OopsApplication.class, args);
    }

}
