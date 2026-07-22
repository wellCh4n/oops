package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "application_expert_config")
@EqualsAndHashCode(callSuper = true)
public class ApplicationExpertConfig extends BaseDataObject {

    private String namespace;

    private String applicationName;

    @Column(name = "environment_configs", columnDefinition = "TEXT")
    @Convert(converter = EnvironmentConfigsConverter.class)
    private List<EnvironmentConfig> environmentConfigs;

    @Data
    public static class EnvironmentConfig {
        private String environmentName;

        private String serviceAccountName;

        private String priority;

        private boolean scheduledRestartEnabled;

        private String scheduledRestartCron;

        private List<String> nodeNames;
    }

    @Converter
    public static class EnvironmentConfigsConverter implements AttributeConverter<List<EnvironmentConfig>, String> {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        private static final TypeReference<List<EnvironmentConfig>> TYPE = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(List<EnvironmentConfig> attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize environmentConfigs", e);
            }
        }

        @Override
        public List<EnvironmentConfig> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.readValue(dbData, TYPE);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize environmentConfigs", e);
            }
        }
    }
}
