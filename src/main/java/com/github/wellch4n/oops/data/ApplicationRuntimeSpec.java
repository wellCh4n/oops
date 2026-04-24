package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "application_runtime_spec")
@EqualsAndHashCode(callSuper = true)
public class ApplicationRuntimeSpec extends BaseDataObject {

    private String namespace;

    private String applicationName;

    @Lob
    @Column(name = "environment_configs")
    @Convert(converter = EnvironmentConfigsConverter.class)
    private List<EnvironmentConfig> environmentConfigs;

    @Lob
    @Column(name = "health_check")
    @Convert(converter = HealthCheckConverter.class)
    private HealthCheck healthCheck;

    @Data
    public static class EnvironmentConfig {
        private String environmentName;

        private String cpuRequest;
        private String cpuLimit;

        private String memoryRequest;
        private String memoryLimit;

        private Integer replicas;
    }

    @Data
    public static class HealthCheck {
        public static final int DEFAULT_INITIAL_DELAY_SECONDS = 30;
        public static final int DEFAULT_PERIOD_SECONDS = 10;
        public static final int DEFAULT_TIMEOUT_SECONDS = 3;
        public static final int DEFAULT_FAILURE_THRESHOLD = 3;
        public static final String DEFAULT_PATH = "/";

        private Boolean enabled = false;

        private String path = DEFAULT_PATH;

        private Integer initialDelaySeconds = DEFAULT_INITIAL_DELAY_SECONDS;

        private Integer periodSeconds = DEFAULT_PERIOD_SECONDS;

        private Integer timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        private Integer failureThreshold = DEFAULT_FAILURE_THRESHOLD;

        public boolean probeEnabled() {
            return Boolean.TRUE.equals(enabled) && path != null && !path.isBlank();
        }

        public String normalizedPath() {
            if (path == null || path.isBlank()) {
                return DEFAULT_PATH;
            }
            return path.startsWith("/") ? path : "/" + path;
        }

        public int effectiveInitialDelaySeconds() {
            return initialDelaySeconds != null && initialDelaySeconds >= 0
                    ? initialDelaySeconds
                    : DEFAULT_INITIAL_DELAY_SECONDS;
        }

        public int effectivePeriodSeconds() {
            return periodSeconds != null && periodSeconds > 0
                    ? periodSeconds
                    : DEFAULT_PERIOD_SECONDS;
        }

        public int effectiveTimeoutSeconds() {
            return timeoutSeconds != null && timeoutSeconds > 0
                    ? timeoutSeconds
                    : DEFAULT_TIMEOUT_SECONDS;
        }

        public int effectiveFailureThreshold() {
            return failureThreshold != null && failureThreshold > 0
                    ? failureThreshold
                    : DEFAULT_FAILURE_THRESHOLD;
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

    @Converter
    public static class HealthCheckConverter implements AttributeConverter<HealthCheck, String> {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(HealthCheck attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize healthCheck", e);
            }
        }

        @Override
        public HealthCheck convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.readValue(dbData, HealthCheck.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize healthCheck", e);
            }
        }
    }
}
