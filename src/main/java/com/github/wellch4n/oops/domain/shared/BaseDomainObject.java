package com.github.wellch4n.oops.domain.shared;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public abstract class BaseDomainObject {
    private String id;
    private LocalDateTime createdTime;
}
