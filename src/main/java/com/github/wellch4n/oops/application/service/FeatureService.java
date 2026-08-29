package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.FeaturesView;
import com.github.wellch4n.oops.infrastructure.config.FeishuProperties;
import com.github.wellch4n.oops.infrastructure.config.IdeProperties;
import com.github.wellch4n.oops.infrastructure.config.ObjectStorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class FeatureService {

    private final FeishuProperties feishuProperties;
    private final ObjectProvider<IdeProperties> idePropertiesProvider;
    private final ObjectStorageProperties objectStorageProperties;

    public FeatureService(FeishuProperties feishuProperties,
                          ObjectProvider<IdeProperties> idePropertiesProvider,
                          ObjectStorageProperties objectStorageProperties) {
        this.feishuProperties = feishuProperties;
        this.idePropertiesProvider = idePropertiesProvider;
        this.objectStorageProperties = objectStorageProperties;
    }

    public FeaturesView getFeatures() {
        IdeProperties ideProperties = idePropertiesProvider.getIfAvailable();
        return new FeaturesView(
                feishuProperties.isEnabled(),
                ideProperties != null,
                ideProperties != null ? ideProperties.getDomain() : null,
                ideProperties != null && ideProperties.isHttps(),
                objectStorageProperties.isEnabled()
        );
    }
}
