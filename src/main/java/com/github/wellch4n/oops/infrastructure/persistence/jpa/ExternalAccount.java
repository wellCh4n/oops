package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "external_account")
@EqualsAndHashCode(callSuper = true)
public class ExternalAccount extends BaseDataObject {
    @Id
    private String id;

    @PrePersist
    public void generateId() {
        if (this.id == null) {
            this.id = NanoIdUtils.generate();
        }
    }

    private String email;

    @Enumerated(EnumType.STRING)
    private ExternalAccountProvider provider;

    @Column(name = "provider_user_id")
    private String providerUserId;

    @Column(name = "user_id")
    private String userId;
}