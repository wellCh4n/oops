package com.github.wellch4n.oops.interfaces.dto;

import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import lombok.Data;

@Data
public class DomainRequest {
    private String host;
    private String description;
    private Boolean https;
    private DomainCertMode certMode;
    private String certPem;
    private String keyPem;
}
