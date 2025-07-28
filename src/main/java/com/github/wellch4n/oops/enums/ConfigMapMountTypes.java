package com.github.wellch4n.oops.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

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
