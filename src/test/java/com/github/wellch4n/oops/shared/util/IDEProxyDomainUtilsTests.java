package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IDEProxyDomainUtilsTests {

    @Test
    void normalizesValidTemplate() {
        var normalized = IDEProxyDomainUtils.normalizeTemplate("  {{port}}-{{host}}  ");

        assertTrue(normalized.isPresent());
        assertEquals("{{port}}-{{host}}", normalized.get());
    }

    @Test
    void rejectsTemplateWithoutHostPlaceholder() {
        var normalized = IDEProxyDomainUtils.normalizeTemplate("{{port}}.ide.example.com");

        assertFalse(normalized.isPresent());
    }

    @Test
    void appendsPortHostRegexpWhenTemplateIsValid() {
        String match = IDEProxyDomainUtils.buildIngressMatch(
                "ds-website-ide-l7n0c8hegacl7iqofvunmy26.ide.ops.dsdigital.team",
                "{{port}}-{{host}}");

        assertEquals(
                "Host(`ds-website-ide-l7n0c8hegacl7iqofvunmy26.ide.ops.dsdigital.team`) || "
                        + "HostRegexp(`^[0-9]+-ds-website-ide-l7n0c8hegacl7iqofvunmy26\\.ide\\.ops\\.dsdigital\\.team$`)",
                match);
    }

    @Test
    void keepsSingleHostMatchWhenTemplateIsMissing() {
        String match = IDEProxyDomainUtils.buildIngressMatch(
                "ds-website-ide-l7n0c8hegacl7iqofvunmy26.ide.ops.dsdigital.team",
                null);

        assertEquals("Host(`ds-website-ide-l7n0c8hegacl7iqofvunmy26.ide.ops.dsdigital.team`)", match);
    }
}
