package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class ApplicationServiceConfig extends BaseDataObject {

    private String namespace;

    private String applicationName;

    private Integer port;

    @Column(name = "internal_ports", columnDefinition = "TEXT")
    @Convert(converter = InternalPortsConverter.class)
    private List<Integer> internalPorts;

    @Column(name = "environment_configs", columnDefinition = "TEXT")
    @Convert(converter = EnvironmentConfigsConverter.class)
    private List<EnvironmentConfig> environmentConfigs;

    @JsonIgnore
    public List<EnvironmentConfig> getEnvironmentConfigs(String environmentName) {
        if (environmentConfigs == null) {
            return List.of();
        }
        return environmentConfigs.stream()
                .filter(config -> environmentName.equals(config.getEnvironment()))
                .toList();
    }

    @Data
    public static class EnvironmentConfig {
        private String environment;

        private String host;

        private Boolean https = true;

        // Omitted from the stored JSON when unset, so a host without basic auth keeps the exact
        // shape it had before the feature existed instead of carrying three nulls.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean basicAuthEnabled;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String basicAuthUsername;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String basicAuthPasswordHash;
    }

    @Converter
    public static class EnvironmentConfigsConverter implements AttributeConverter<List<EnvironmentConfig>, String> {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
                // A JSON blob column outlives the shape that wrote it: rows written by an older
                // version can carry keys this class no longer has, and those must not break reads.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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

    @Converter
    public static class InternalPortsConverter implements AttributeConverter<List<Integer>, String> {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
                // A JSON blob column outlives the shape that wrote it: rows written by an older
                // version can carry keys this class no longer has, and those must not break reads.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        private static final TypeReference<List<Integer>> TYPE = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(List<Integer> attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize internalPorts", e);
            }
        }

        @Override
        public List<Integer> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.readValue(dbData, TYPE);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize internalPorts", e);
            }
        }
    }
}
