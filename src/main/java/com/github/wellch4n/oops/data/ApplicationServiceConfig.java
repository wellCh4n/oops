package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.utils.NanoIdUtils;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class ApplicationServiceConfig extends BaseDataObject {
    @Id
    private String id;

    @PrePersist
    public void generateId() {
        if (this.id == null) {
            this.id = NanoIdUtils.generate();
        }
    }

    private String namespace;

    private String applicationName;

    private Integer port;

    @Lob
    @Column(name = "environment_configs")
    @Convert(converter = EnvironmentConfigsConverter.class)
    private List<EnvironmentConfig> environmentConfigs;

    @JsonIgnore
    public EnvironmentConfig getEnvironmentConfig(String environmentName) {
        if (environmentConfigs == null) {
            return null;
        }
        return environmentConfigs.stream()
                .filter(config -> environmentName.equals(config.getEnvironmentName()))
                .findFirst()
                .orElse(null);
    }

    @Data
    public static class EnvironmentConfig {
        private String environmentName;

        private String host;

        private Boolean https = true;
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
