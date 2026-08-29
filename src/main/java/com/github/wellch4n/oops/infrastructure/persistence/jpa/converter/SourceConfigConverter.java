package com.github.wellch4n.oops.infrastructure.persistence.jpa.converter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.wellch4n.oops.domain.application.SourceConfig;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SourceConfigConverter implements AttributeConverter<SourceConfig, String> {

    // Ignore unknown properties so JSON written by an older schema still deserializes after fields are added.
    // Allow empty beans so a variant with no fields yet (e.g. ZipSourceConfig) still serializes.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    @Override
    public String convertToDatabaseColumn(SourceConfig attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize sourceConfig", e);
        }
    }

    @Override
    public SourceConfig convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, SourceConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize sourceConfig", e);
        }
    }
}
