package com.github.wellch4n.oops.objects;

import lombok.Data;

@Data
public class IDECreateRequest {
    private String branch;
    private String settings;
    private String env;
    private String extensions;
}
