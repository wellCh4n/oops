package com.github.wellch4n.oops.domain.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainSharedEnumTests {

    @Test
    void configMapMountTypes_keys_returnsAllKeys() {
        List<String> keys = ConfigMapMountTypes.keys();

        assertFalse(keys.isEmpty());
        assertTrue(keys.contains(".mount.path.keys"));
    }

    @Test
    void configMapMountTypes_valueOf_resolvesPath() {
        assertEquals(ConfigMapMountTypes.PATH, ConfigMapMountTypes.valueOf("PATH"));
        assertEquals(".mount.path.keys", ConfigMapMountTypes.PATH.getKey());
    }

    @Test
    void oopsTypes_hasExpectedValues() {
        assertEquals(4, OopsTypes.values().length);
        assertNotNull(OopsTypes.valueOf("PIPELINE"));
        assertNotNull(OopsTypes.valueOf("APPLICATION"));
        assertNotNull(OopsTypes.valueOf("STORAGE"));
        assertNotNull(OopsTypes.valueOf("IDE"));
    }

    @Test
    void systemConfigKeys_hasExpectedValues() {
        assertEquals(5, SystemConfigKeys.values().length);
        assertNotNull(SystemConfigKeys.valueOf("KUBERNETES_API_SERVER_URL"));
        assertNotNull(SystemConfigKeys.valueOf("KUBERNETES_API_SERVER_TOKEN"));
        assertNotNull(SystemConfigKeys.valueOf("IMAGE_REPOSITORY_URL"));
        assertNotNull(SystemConfigKeys.valueOf("WORK_NAMESPACE"));
        assertNotNull(SystemConfigKeys.valueOf("BUILD_CACHE_STORAGE_CLASS"));
    }
}
