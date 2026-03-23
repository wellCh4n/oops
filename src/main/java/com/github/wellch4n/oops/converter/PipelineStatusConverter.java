package com.github.wellch4n.oops.converter;

import com.github.wellch4n.oops.enums.PipelineStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PipelineStatusConverter implements AttributeConverter<PipelineStatus, String> {

    @Override
    public String convertToDatabaseColumn(PipelineStatus attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public PipelineStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PipelineStatus.valueOf(dbData);
    }
}
