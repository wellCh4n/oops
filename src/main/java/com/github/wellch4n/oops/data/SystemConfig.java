package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.SystemConfigKeys;
import jakarta.persistence.*;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/6
 */

@Data
@Entity
public class SystemConfig {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(128)")
    private SystemConfigKeys configKey;

    @Column(columnDefinition = "TEXT")
    private String configValue;
}
