package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.util.PemCertificateParser.CertMeta;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PemCertificateParserTests {

    // Public self-signed certificate (CN=example.com, SAN: example.com, www.example.com), valid until 2126.
    // Inlined rather than kept as a resource file so the repository carries no certificate/key files.
    // A public certificate contains no secret material; the JDK has no public API to generate an X.509
    // certificate at runtime, so this is the lightest way to exercise parseCertificate(). Private keys,
    // by contrast, ARE generated at runtime below.
    private static final String CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDODCCAiCgAwIBAgIUXLJgQPqvuUGvVNO99rZFBlE/3QswDQYJKoZIhvcNAQEL
            BQAwFjEUMBIGA1UEAwwLZXhhbXBsZS5jb20wIBcNMjYwNjI3MTY1ODMyWhgPMjEy
            NjA2MDMxNjU4MzJaMBYxFDASBgNVBAMMC2V4YW1wbGUuY29tMIIBIjANBgkqhkiG
            9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3WTEOFXnBHacY7n5j8ILq21FISdqq9Zo6g3E
            7mN/ro1NTnscdgxU7n7ivbz61sl3eKnSC+e/xyCEDi+DMZWsiDQjeFTWsjKUssy7
            QI4VR1IAqtkQmLPQNlqWmeAcYWXdYj/LymDs1iczicPgHQSVJBj5Y5siAjPwSTq6
            HEuakZoaYogte44bQl74Wb/H34KmrOuPWjw7nQP7fNjA/7Ilj8fTSh8FROmviFsG
            s9OjEI1p1nkvUaMPHa4KSmZnBFNFHmW7fsGH4ohNBwtOZSl/+c+U3NfQ5vqhdGoK
            00FliYmX+DpjuYeXq82WciaaQxi+P32hXrb7Dpxk6TiqTeNk2wIDAQABo3wwejAd
            BgNVHQ4EFgQUOh0PHxI8U0Qk8KDfb6+pg23T0k4wHwYDVR0jBBgwFoAUOh0PHxI8
            U0Qk8KDfb6+pg23T0k4wDwYDVR0TAQH/BAUwAwEB/zAnBgNVHREEIDAeggtleGFt
            cGxlLmNvbYIPd3d3LmV4YW1wbGUuY29tMA0GCSqGSIb3DQEBCwUAA4IBAQCdbjSJ
            26E7xtLoywxyHz/uYbeVMW+ZtQqHpDD5TgvZDLB5yAGShq3wJli22imsLyloHjjG
            R6D7yPcJt9Fs+pXfxNNV+odZ1N6T3GWNZeatOBQpr1wmh1Ij7wu59IE+Tw6vLPMa
            LjEE3vXtTUzINX2Z2eE1cabKppuB9xXoxQqan3R8iAxuuJeG4ngXdaJQO36U4c3t
            jZr9QsqIRRvBSCYohZGX5cJizhrbUKMQBt+uNxMGSVo9OWt4Ff9luiS8muTOGCs1
            Mds/9p3lJdzsNpYcuimh9h2A00Z84Xb6qc0dYExrcpDmZG85ip4UcP/d6NuZuBDl
            +6O0CtYxit3sgmtb
            -----END CERTIFICATE-----
            """;

    private String pkcs8Pem(String algorithm, ECGenParameterSpec ecSpec) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if (ecSpec != null) {
            generator.initialize(ecSpec);
        } else {
            generator.initialize(2048);
        }
        PrivateKey privateKey = generator.generateKeyPair().getPrivate();
        // PrivateKey.getEncoded() is PKCS#8 DER by contract.
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void parseCertificateExtractsSubjectAndDnsNames() {
        CertMeta meta = PemCertificateParser.parseCertificate(CERT_PEM);
        assertTrue(meta.getSubject().contains("CN=example.com"));
        assertTrue(meta.getNotAfter().isAfter(LocalDateTime.now()));
        // CN + SAN entries, lowercased and de-duplicated
        assertTrue(meta.getDnsNames().contains("example.com"));
        assertTrue(meta.getDnsNames().contains("www.example.com"));
        // "example.com" appears as both CN and SAN but must be de-duplicated
        long exampleCount = meta.getDnsNames().stream().filter("example.com"::equals).count();
        assertEquals(1, exampleCount, "example.com should not be duplicated");
    }

    @Test
    void parseCertificateRejectsEmptyAndInvalid() {
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.parseCertificate(null));
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.parseCertificate("  "));
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.parseCertificate("not a pem"));
    }

    @Test
    void hostMatchesIsCaseInsensitiveAndWildcardAware() {
        List<String> dnsNames = List.of("example.com", "*.example.com");
        assertTrue(PemCertificateParser.hostMatches("example.com", dnsNames));
        assertTrue(PemCertificateParser.hostMatches("EXAMPLE.COM", dnsNames));
        // both sides strip the wildcard prefix before comparing
        assertTrue(PemCertificateParser.hostMatches("*.example.com", dnsNames));
    }

    @Test
    void hostMatchesFalseForNoMatchOrEmptyInputs() {
        assertFalse(PemCertificateParser.hostMatches("other.com", List.of("example.com")));
        assertFalse(PemCertificateParser.hostMatches(null, List.of("example.com")));
        assertFalse(PemCertificateParser.hostMatches("example.com", List.of()));
        assertFalse(PemCertificateParser.hostMatches("example.com", null));
    }

    @Test
    void validatePrivateKeyAcceptsPkcs8RsaAndEc() throws Exception {
        assertDoesNotThrow(() -> PemCertificateParser.validatePrivateKey(pkcs8Pem("RSA", null)));
        assertDoesNotThrow(() ->
                PemCertificateParser.validatePrivateKey(pkcs8Pem("EC", new ECGenParameterSpec("secp256r1"))));
    }

    @Test
    void validatePrivateKeyRejectsLegacyPkcs1() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJB\n-----END RSA PRIVATE KEY-----\n";
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PemCertificateParser.validatePrivateKey(pkcs1));
        assertTrue(exception.getMessage().contains("PKCS#1"));
    }

    @Test
    void validatePrivateKeyRejectsEmptyAndNonPem() {
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.validatePrivateKey(null));
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.validatePrivateKey("  "));
        assertThrows(IllegalArgumentException.class, () -> PemCertificateParser.validatePrivateKey("garbage"));
    }
}
