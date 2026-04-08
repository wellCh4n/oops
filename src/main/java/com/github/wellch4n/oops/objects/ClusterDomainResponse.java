package com.github.wellch4n.oops.objects;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ClusterDomainResponse {
    private String internalDomain;
    private List<String> externalDomains;
}
