package com.github.wellch4n.oops.infrastructure.persistence.jpa.converter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.domain.delivery.PublishConfig;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class PublishConfigConverter implements AttributeConverter<PublishConfig, String> {

    // Ignore unknown properties so JSON written by an older schema still deserializes after fields are added.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(PublishConfig attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize publishConfig", e);
        }
    }

    @Override
    public PublishConfig convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, PublishConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize publishConfig", e);
        }
    }
}
