package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.routing.Domain;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainDto {
    private String id;
    private String host;
    private String description;
    private Boolean https;
    private DomainCertMode certMode;
    private Boolean hasUploadedCert;
    private String certSubject;
    private LocalDateTime certNotAfter;
    private LocalDateTime createdTime;

    public static DomainDto from(Domain domain) {
        return DomainDto.builder()
                .id(domain.getId())
                .host(domain.getHost())
                .description(domain.getDescription())
                .https(domain.getHttps())
                .certMode(domain.getCertMode())
                .hasUploadedCert(domain.getCertPem() != null && !domain.getCertPem().isBlank())
                .certSubject(domain.getCertSubject())
                .certNotAfter(domain.getCertNotAfter())
                .createdTime(domain.getCreatedTime())
                .build();
    }
}
