package com.github.wellch4n.oops.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseDataObject {

    @Column(unique = true)
    private String name;

    private String description;

    private String namespace;

    private String owner;
}
