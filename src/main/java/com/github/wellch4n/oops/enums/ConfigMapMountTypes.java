package com.github.wellch4n.oops.enums;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */
public enum ConfigMapMountTypes {
    PATH(".mount.path.keys"),
    ;

    @Getter
    final String key;

    ConfigMapMountTypes(String key){
        this.key = key;
    }

    public static List<String> keys() {
        return Arrays.stream(ConfigMapMountTypes.values()).map(ConfigMapMountTypes::getKey).toList();
    }
}
