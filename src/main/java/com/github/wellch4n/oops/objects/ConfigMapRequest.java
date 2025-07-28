package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.ConfigMapMountTypes;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/26
 */

@Data
public class ConfigMapRequest {
    private String key;
    private String value;
    private Boolean mountAsPath;
}
