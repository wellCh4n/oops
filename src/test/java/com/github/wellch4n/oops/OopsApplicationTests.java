package com.github.wellch4n.oops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Inline properties have the highest precedence (above any external config/application.yml),
// so the smoke test always boots against in-memory H2 with Flyway disabled — both locally
// (where ./config/application.yml points at MySQL) and in CI (where that file is absent).
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:oops;DB_CLOSE_DELAY=-1;MODE=MySQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "oops.jwt.secret=test-jwt-secret-key-which-is-long-enough-0123456789",
        "oops.jwt.expiration=86400000",
        "oops.crypto.secret-key=test-crypto-secret-key-0123456789"
})
@ActiveProfiles("test")
class OopsApplicationTests {

    @Test
    void contextLoads() {
    }

}
