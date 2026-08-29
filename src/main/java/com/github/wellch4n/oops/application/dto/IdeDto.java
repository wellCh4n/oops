package com.github.wellch4n.oops.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IdeDto {

    private String id;
    private String name;
    private String host;
    private boolean https;
    private String createdAt;
    private boolean ready;
}
