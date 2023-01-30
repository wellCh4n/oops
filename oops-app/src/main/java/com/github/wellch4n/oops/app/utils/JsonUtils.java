package com.github.wellch4n.oops.app.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public class JsonUtils {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static <T> T convert(String str, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(str, typeReference);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
