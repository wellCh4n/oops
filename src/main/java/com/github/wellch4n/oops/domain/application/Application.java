package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseAggregateRoot {
    private String name;
    private String description;
    private String namespace;
    private String owner;

    public void placeInNamespace(String namespace) {
        this.namespace = namespace;
    }

    public void changeProfile(String description, String owner) {
        this.description = description;
        this.owner = owner;
    }
}
