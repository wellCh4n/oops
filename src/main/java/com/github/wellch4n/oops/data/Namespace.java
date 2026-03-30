package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.utils.NanoIdUtils;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Namespace extends BaseDataObject {
    @Id
    private String id;

    @PrePersist
    public void generateId() {
        if (this.id == null) {
            this.id = NanoIdUtils.generate();
        }
    }

    private String name;
    
    private String description;
}
