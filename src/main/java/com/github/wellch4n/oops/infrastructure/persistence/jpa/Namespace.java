package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Namespace extends BaseDataObject {

    private String name;

    private String description;
}
