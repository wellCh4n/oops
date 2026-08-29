package com.github.wellch4n.oops.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FeaturesView {
    private boolean feishu;
    private boolean ide;
    private String ideHost;
    private boolean ideHttps;
    private boolean objectStorage;
}
