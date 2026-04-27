package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.DockerFileType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class ApplicationBuildConfig extends BaseDataObject {

    private String namespace;

    private String applicationName;

    @Enumerated(EnumType.STRING)
    private ApplicationSourceType sourceType;

    private String repository;

    @Lob
    @Column(name = "docker_file_config")
    @Convert(converter = DockerFileConfigConverter.class)
    private DockerFileConfig dockerFileConfig;

    private String buildImage;

    @Lob
    @Column(name = "environment_configs")
    @Convert(converter = EnvironmentConfigsConverter.class)
    private List<EnvironmentConfig> environmentConfigs;

    @Data
    public static class DockerFileConfig {

        private DockerFileType type;

        private String path;

        private String content;
    }

    @Data
    public static class EnvironmentConfig {

        private String environmentName;

        private String buildCommand;
    }

    @Converter
    public static class DockerFileConfigConverter implements AttributeConverter<DockerFileConfig, String> {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(DockerFileConfig attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize dockerFileConfig", e);
            }
        }

        @Override
        public DockerFileConfig convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.readValue(dbData, DockerFileConfig.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize dockerFileConfig", e);
            }
        }
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
