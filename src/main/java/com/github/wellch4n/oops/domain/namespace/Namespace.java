package com.github.wellch4n.oops.domain.namespace;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Namespace extends BaseAggregateRoot {
    private String name;
    private String description;
}
