package com.github.wellch4n.oops.infrastructure.persistence.jpa.converter;

import com.github.wellch4n.oops.domain.shared.DeployMode;
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
