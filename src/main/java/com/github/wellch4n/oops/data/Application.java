package com.github.wellch4n.oops.data;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseDataObject {

    private String name;

    private String description;

    private String namespace;

    private String owner;
}
