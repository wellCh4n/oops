package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.domain.sandbox.BuiltinSandboxRuntime;
import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SandboxRuntimeService {

    private final SandboxProperties sandboxProperties;

    public SandboxRuntimeService(SandboxProperties sandboxProperties) {
        this.sandboxProperties = sandboxProperties;
    }

    public List<String> list() {
        Stream<String> builtins = Arrays.stream(BuiltinSandboxRuntime.values())
                .map(BuiltinSandboxRuntime::getKey);

        Stream<String> customs = sandboxProperties.getImages().stream();

        return Stream.concat(builtins, customs).sorted().toList();
    }
}
