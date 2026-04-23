package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.wellch4n.oops.enums.DomainCertMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Domain extends BaseDataObject {

    @Column(unique = true)
    private String host;

    private String description;

    private Boolean https;

    @Enumerated(EnumType.STRING)
    private DomainCertMode certMode;

    @JsonIgnore
    @Lob
    @Column(columnDefinition = "TEXT")
    private String certPem;

    @JsonIgnore
    @Lob
    @Column(columnDefinition = "TEXT")
    private String keyPem;

    private String certSubject;

    private LocalDateTime certNotAfter;
}
