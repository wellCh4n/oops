package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
    @Convert(converter = com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.EncryptedStringConverter.class)
    private String certPem;

    @JsonIgnore
    @Lob
    @Column(columnDefinition = "TEXT")
    @Convert(converter = com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.EncryptedStringConverter.class)
    private String keyPem;

    private String certSubject;

    private LocalDateTime certNotAfter;
}
