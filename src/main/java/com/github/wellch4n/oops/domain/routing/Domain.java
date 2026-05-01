package com.github.wellch4n.oops.domain.routing;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Domain extends BaseAggregateRoot {
    private String host;
    private String description;
    private Boolean https;
    private DomainCertMode certMode;
    private String certPem;
    private String keyPem;
    private String certSubject;
    private LocalDateTime certNotAfter;
}
