package com.github.wellch4n.oops.application.dto;

import lombok.Data;

@Data
public class IDECreateRequest {
    private String name;
    private String branch;
    private String settings;
    private String env;
    private String extensions;
}
