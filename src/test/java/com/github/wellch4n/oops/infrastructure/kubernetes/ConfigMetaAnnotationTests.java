package com.github.wellch4n.oops.infrastructure.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesConfigMapGateway.ConfigMeta;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigMetaAnnotationTests {

    @Test
    void ofNormalizesBlanksToNullAndDropsEmptyMeta() {
        assertNull(ConfigMeta.of("  ", "\t", null));
        assertNull(ConfigMeta.of(null, null, null));

        ConfigMeta groupOnly = ConfigMeta.of(" database ", "", null);
        assertEquals("database", groupOnly.group());
        assertNull(groupOnly.comment());

        // Order alone is enough to keep the meta (drag-reordered items may have no group/comment).
        ConfigMeta orderOnly = ConfigMeta.of("", "", 3);
        assertNull(orderOnly.group());
        assertEquals(3, orderOnly.order());
    }

    @Test
    void annotationsSerializeMetaAndMountsIndependently() {
        Map<String, String> mounts = Map.of("app.yml", "/etc/app/app.yml");
        Map<String, ConfigMeta> metas = new LinkedHashMap<>();
        metas.put("DB_HOST", new ConfigMeta("database", "primary host", 0));

        Map<String, String> annotations = KubernetesConfigMapGateway.annotations(mounts, metas);

        assertTrue(annotations.containsKey(KubernetesConfigMapGateway.MOUNT_ANNOTATION));
        assertTrue(annotations.containsKey(KubernetesConfigMapGateway.CONFIG_META_ANNOTATION));
    }

    @Test
    void emptyMapsProduceNoAnnotations() {
        Map<String, String> annotations = KubernetesConfigMapGateway.annotations(Map.of(), Map.of());
        assertTrue(annotations.isEmpty());
    }

    @Test
    void metaSurvivesAnnotationRoundTrip() {
        Map<String, ConfigMeta> original = new LinkedHashMap<>();
        original.put("DB_HOST", new ConfigMeta("database", "primary host", 0));
        original.put("DB_PORT", new ConfigMeta("database", null, 1));
        original.put("FLAG", new ConfigMeta(null, "just a note", 2));

        Map<String, String> annotations = KubernetesConfigMapGateway.annotations(Map.of(), original);
        ObjectMeta metadata = new ObjectMetaBuilder().withAnnotations(annotations).build();

        Map<String, ConfigMeta> restored = KubernetesConfigMapGateway.readConfigMetas(metadata);

        assertEquals(original, restored);
    }

    @Test
    void legacyMetaWithoutOrderStillDeserializes() {
        // Meta written before the order field existed must still load, with order left null.
        ObjectMeta legacy = new ObjectMetaBuilder()
                .withAnnotations(Map.of(
                        KubernetesConfigMapGateway.CONFIG_META_ANNOTATION,
                        "{\"DB_HOST\":{\"group\":\"database\",\"comment\":\"host\"}}"))
                .build();

        Map<String, ConfigMeta> restored = KubernetesConfigMapGateway.readConfigMetas(legacy);

        assertEquals("database", restored.get("DB_HOST").group());
        assertNull(restored.get("DB_HOST").order());
    }

    @Test
    void readConfigMetasReturnsEmptyWhenAnnotationMissingOrGarbled() {
        assertTrue(KubernetesConfigMapGateway.readConfigMetas(null).isEmpty());
        assertTrue(KubernetesConfigMapGateway.readConfigMetas(new ObjectMetaBuilder().build()).isEmpty());

        ObjectMeta garbled = new ObjectMetaBuilder()
                .withAnnotations(Map.of(KubernetesConfigMapGateway.CONFIG_META_ANNOTATION, "not-json"))
                .build();
        assertTrue(KubernetesConfigMapGateway.readConfigMetas(garbled).isEmpty());
    }
}
