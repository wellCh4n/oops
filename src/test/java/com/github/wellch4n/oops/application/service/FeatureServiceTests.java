package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.FeaturesView;
import com.github.wellch4n.oops.infrastructure.config.FeishuProperties;
import com.github.wellch4n.oops.infrastructure.config.IdeProperties;
import com.github.wellch4n.oops.infrastructure.config.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FeatureServiceTests {

    @SuppressWarnings("unchecked")
    @Test
    void getFeaturesWhenIdeDisabled() {
        FeishuProperties feishu = mock(FeishuProperties.class);
        when(feishu.isEnabled()).thenReturn(false);
        ObjectProvider<IdeProperties> ideProvider = mock(ObjectProvider.class);
        when(ideProvider.getIfAvailable()).thenReturn(null);
        ObjectStorageProperties storage = mock(ObjectStorageProperties.class);
        when(storage.isEnabled()).thenReturn(false);

        FeatureService service = new FeatureService(feishu, ideProvider, storage);
        FeaturesView features = service.getFeatures();

        assertFalse(features.isFeishu());
        assertFalse(features.isIde());
        assertNull(features.getIdeHost());
        assertFalse(features.isIdeHttps());
        assertFalse(features.isObjectStorage());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getFeaturesWhenIdeEnabled() {
        FeishuProperties feishu = mock(FeishuProperties.class);
        when(feishu.isEnabled()).thenReturn(true);
        IdeProperties ideProperties = mock(IdeProperties.class);
        when(ideProperties.getDomain()).thenReturn("ide.example.com");
        when(ideProperties.isHttps()).thenReturn(true);
        ObjectProvider<IdeProperties> ideProvider = mock(ObjectProvider.class);
        when(ideProvider.getIfAvailable()).thenReturn(ideProperties);
        ObjectStorageProperties storage = mock(ObjectStorageProperties.class);
        when(storage.isEnabled()).thenReturn(true);

        FeatureService service = new FeatureService(feishu, ideProvider, storage);
        FeaturesView features = service.getFeatures();

        assertTrue(features.isFeishu());
        assertTrue(features.isIde());
        assertNotNull(features.getIdeHost());
        assertTrue(features.isIdeHttps());
        assertTrue(features.isObjectStorage());
    }
}
