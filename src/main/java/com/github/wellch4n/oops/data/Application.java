package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.utils.NanoIdUtils;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseDataObject {
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

    private String namespace;
}
