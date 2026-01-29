package com.github.wellch4n.oops.data;

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

    private LocalDateTime createdTime;

    @PrePersist
    public void prePersist() {
        this.createdTime = LocalDateTime.now();
    }
}
