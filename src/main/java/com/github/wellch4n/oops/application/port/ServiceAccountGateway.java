package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

public interface ServiceAccountGateway {
    List<String> listServiceAccountNames(Environment environment, String namespace);
}
