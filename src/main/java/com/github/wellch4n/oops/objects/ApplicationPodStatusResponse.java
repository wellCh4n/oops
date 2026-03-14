package com.github.wellch4n.oops.objects;

import lombok.Data;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */


@Data
public class ApplicationPodStatusResponse {

    private String name;
    private String namespace;
    private String status;

    private List<String> image;
    private String podIP;
}
