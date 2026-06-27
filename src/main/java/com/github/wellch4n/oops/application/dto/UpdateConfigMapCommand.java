package com.github.wellch4n.oops.application.dto;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/26
 */

@Data
public class UpdateConfigMapCommand {
    private String key;
    private String value;

    /**
     * When true the item is stored in the application Secret; otherwise in the application ConfigMap.
     */
    private boolean secret;

    /**
     * Optional absolute file path. When set, the item is mounted as a file at this path inside the
     * container; when blank the item is exposed as an environment variable only.
     */
    private String mountPath;
}
