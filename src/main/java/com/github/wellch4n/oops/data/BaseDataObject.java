package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.utils.NanoIdUtils;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */
@Data
@MappedSuperclass
public class BaseDataObject {

    @Id
    private String id;

    private LocalDateTime createdTime;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = NanoIdUtils.generate();
        }
        this.createdTime = LocalDateTime.now();
    }
}
