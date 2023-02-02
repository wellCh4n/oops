package com.github.wellch4n.oops.app.pipline;

import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public class PipelineContext extends HashMap<String, Object> {

    @Override
    public Object get(Object key) {
        if (key instanceof String k) {
            if (placeholder(k)) {
                Pair<String, String> placeholderKey = getPlaceholderKey(k);
                Map<String, Object> m = (Map<String, Object>) super.get(placeholderKey.getKey());
                return m.get(placeholderKey.getValue());
            }
        }
        return super.get(key);
    }

    public static boolean placeholder(String k) {
        return (k.startsWith("${") && k.endsWith("}"));
    }

    public static Pair<String, String> getPlaceholderKey(String k) {
        String key =  k.replace("${", "").replace("}", "");
        String[] split = key.split("\\.");
        return Pair.of(split[0], split[1]);
    }
}
