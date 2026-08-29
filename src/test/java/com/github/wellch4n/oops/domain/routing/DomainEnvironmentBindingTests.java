package com.github.wellch4n.oops.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEnvironmentBindingTests {

    private Domain domain(String host, String environmentName) {
        Domain domain = new Domain();
        domain.setHost(host);
        domain.setEnvironment(environmentName);
        return domain;
    }

    @Test
    void allowsOnlyTheBoundEnvironment() {
        Domain domain = domain("test.dsdigital.team", "Test");
        assertTrue(domain.allowsEnvironment("Test"));
        assertFalse(domain.allowsEnvironment("Production"));
    }

    @Test
    void unboundDomainAllowsNothing() {
        Domain domain = domain("dsdigital.team", null);
        assertFalse(domain.allowsEnvironment("Test"));
    }

    @Test
    void bestMatchResolvesTheGoverningDomainBySuffixLength() {
        DomainPolicy policy = new DomainPolicy();
        Domain production = domain("dsdigital.team", "Production");
        Domain test = domain("test.dsdigital.team", "Test");
        List<Domain> domains = List.of(production, test);

        Domain governing = policy.findBestMatch("app.test.dsdigital.team", domains, Domain::getHost).orElseThrow();
        assertEquals("test.dsdigital.team", governing.getHost());
        assertTrue(governing.allowsEnvironment("Test"));

        Domain productionGoverning = policy.findBestMatch("app.dsdigital.team", domains, Domain::getHost).orElseThrow();
        assertEquals("dsdigital.team", productionGoverning.getHost());
        assertFalse(productionGoverning.allowsEnvironment("Test"));
    }
}
