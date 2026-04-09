package com.github.wellch4n.oops.objects;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ClusterDomainResponse {
    private String internalDomain;
    private List<String> externalDomains;
}
