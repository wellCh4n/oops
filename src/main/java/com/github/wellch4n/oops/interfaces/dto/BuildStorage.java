package com.github.wellch4n.oops.interfaces.dto;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/23
 */

@Data
public class BuildStorage {
    private String path;
    private String capacity;

    private String pvcName;
}
