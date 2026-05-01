package com.github.wellch4n.oops.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IDEConfigResponse {
    private String settings;
    private String env;
    private String extensions;
}
