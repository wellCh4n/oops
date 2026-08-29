package com.github.wellch4n.oops.application.port.external;

import java.util.List;

public record ExternalUserMessage(
        String title,
        ExternalMessageLevel level,
        List<ExternalUserFact> facts,
        String detail,
        String artifact
) {
}
