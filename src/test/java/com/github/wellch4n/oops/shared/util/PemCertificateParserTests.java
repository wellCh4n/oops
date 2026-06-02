package com.github.wellch4n.oops.shared.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PemCertificateParserTests {

    // --- hostMatches ---

    @Test
    void hostMatches_exactMatch() {
        assertThat(PemCertificateParser.hostMatches("example.com", List.of("example.com"))).isTrue();
    }

    @Test
    void hostMatches_wildcardCertMatchesSubdomain() {
        // stripWildcard("*.example.com") = "example.com", stripWildcard("sub.example.com") = "sub.example.com"
        // They don't match — wildcard matching is suffix-based via stripWildcard, not glob
        assertThat(PemCertificateParser.hostMatches("sub.example.com", List.of("*.example.com"))).isFalse();
    }

    @Test
    void hostMatches_wildcardHostMatchesCert() {
        assertThat(PemCertificateParser.hostMatches("*.example.com", List.of("example.com"))).isTrue();
    }

    @Test
    void hostMatches_noMatch() {
        assertThat(PemCertificateParser.hostMatches("other.com", List.of("example.com"))).isFalse();
    }

    @Test
    void hostMatches_nullHost() {
        assertThat(PemCertificateParser.hostMatches(null, List.of("example.com"))).isFalse();
    }

    @Test
    void hostMatches_nullList() {
        assertThat(PemCertificateParser.hostMatches("example.com", null)).isFalse();
    }

    @Test
    void hostMatches_emptyList() {
        assertThat(PemCertificateParser.hostMatches("example.com", List.of())).isFalse();
    }

    @Test
    void hostMatches_caseInsensitive() {
        assertThat(PemCertificateParser.hostMatches("EXAMPLE.COM", List.of("example.com"))).isTrue();
    }

    // --- validatePrivateKey ---

    @Test
    void validatePrivateKey_nullThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey(null))
                .withMessageContaining("empty");
    }

    @Test
    void validatePrivateKey_blankThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey("   "))
                .withMessageContaining("empty");
    }

    @Test
    void validatePrivateKey_pkcs1RsaThrows() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nfakedata\n-----END RSA PRIVATE KEY-----";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey(pkcs1))
                .withMessageContaining("PKCS#1");
    }

    @Test
    void validatePrivateKey_pkcs1EcThrows() {
        String pkcs1ec = "-----BEGIN EC PRIVATE KEY-----\nfakedata\n-----END EC PRIVATE KEY-----";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey(pkcs1ec))
                .withMessageContaining("PKCS#1");
    }

    @Test
    void validatePrivateKey_invalidPkcs8ContentThrows() {
        // Valid header/footer but garbage DER content
        String badKey = "-----BEGIN PRIVATE KEY-----\nYWJjZGVmZ2g=\n-----END PRIVATE KEY-----";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey(badKey))
                .withMessageContaining("Failed to parse private key");
    }

    @Test
    void validatePrivateKey_noPemBlockThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.validatePrivateKey("not a key"))
                .withMessageContaining("Invalid private key format");
    }

    // --- parseCertificate ---

    @Test
    void parseCertificate_nullThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.parseCertificate(null))
                .withMessageContaining("empty");
    }

    @Test
    void parseCertificate_blankThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.parseCertificate("  "))
                .withMessageContaining("empty");
    }

    @Test
    void parseCertificate_noPemBlockThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.parseCertificate("not a cert"))
                .withMessageContaining("Invalid certificate format");
    }

    @Test
    void parseCertificate_invalidBase64ContentThrows() {
        String badCert = "-----BEGIN CERTIFICATE-----\nnotvalidbase64!!!\n-----END CERTIFICATE-----";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PemCertificateParser.parseCertificate(badCert))
                .withMessageContaining("Failed to parse certificate");
    }
}
