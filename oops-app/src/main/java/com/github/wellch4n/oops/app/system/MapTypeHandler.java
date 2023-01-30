package com.github.wellch4n.oops.app.system;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.wellch4n.oops.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.util.Map;

@Slf4j
@MappedTypes({Map.class})
@MappedJdbcTypes(JdbcType.VARCHAR)
public class MapTypeHandler extends JacksonTypeHandler {
    public MapTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    protected Object parse(String json) {
        return JsonUtils.convert(json, new TypeReference<Map<String, Object>>() {});
    }
}
