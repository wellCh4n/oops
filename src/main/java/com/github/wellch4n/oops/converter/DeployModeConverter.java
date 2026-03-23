package com.github.wellch4n.oops.converter;

import com.github.wellch4n.oops.enums.DeployMode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeployModeConverter implements AttributeConverter<DeployMode, String> {

    @Override
    public String convertToDatabaseColumn(DeployMode attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public DeployMode convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DeployMode.valueOf(dbData);
    }
}
