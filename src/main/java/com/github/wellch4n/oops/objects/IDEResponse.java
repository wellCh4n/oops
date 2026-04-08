package com.github.wellch4n.oops.objects;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IDEResponse {

    private String id;
    private String name;
    private String host;
    private boolean https;
    private String createdAt;
    private boolean ready;
}
