package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseDomainObject;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationCollaborator extends BaseDomainObject {
    private String namespace;
    private String applicationName;
    private String userId;
}
