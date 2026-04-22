package com.github.wellch4n.oops.utils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;

public final class PemCertificateParser {

    private static final Pattern CERT_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            Pattern.DOTALL);

    private static final Pattern PKCS8_KEY_BLOCK = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----",
            Pattern.DOTALL);

    private static final Pattern LEGACY_RSA_KEY_BLOCK = Pattern.compile(
            "-----BEGIN (RSA|EC) PRIVATE KEY-----",
            Pattern.DOTALL);

    private PemCertificateParser() {}

    private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+)");

    public static CertMeta parseCertificate(String certPem) {
        if (certPem == null || certPem.isBlank()) {
            throw new IllegalArgumentException("证书内容为空");
        }
        Matcher m = CERT_BLOCK.matcher(certPem);
        if (!m.find()) {
            throw new IllegalArgumentException("证书格式不正确，应为 PEM（-----BEGIN CERTIFICATE-----）");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
            LocalDateTime notAfter = cert.getNotAfter().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
            String subject = cert.getSubjectX500Principal().getName();
            return new CertMeta(subject, notAfter, extractDnsNames(cert, subject));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析证书：" + e.getMessage(), e);
        }
    }

    private static List<String> extractDnsNames(X509Certificate cert, String subject) throws CertificateParsingException {
        List<String> names = new ArrayList<>();
        Matcher cn = CN_PATTERN.matcher(subject);
        if (cn.find()) {
            names.add(cn.group(1).trim().toLowerCase());
        }
        var altNames = cert.getSubjectAlternativeNames();
        if (altNames != null) {
            for (List<?> entry : altNames) {
                // entry[0] = type (2 = dNSName), entry[1] = value
                if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                    Object value = entry.get(1);
                    if (value instanceof String s) {
                        String lower = s.trim().toLowerCase();
                        if (!names.contains(lower)) {
                            names.add(lower);
                        }
                    }
                }
            }
        }
        return names;
    }

    public static boolean hostMatches(String host, List<String> certDnsNames) {
        if (host == null || certDnsNames == null || certDnsNames.isEmpty()) return false;
        String normalizedHost = stripWildcard(host);
        for (String cn : certDnsNames) {
            if (stripWildcard(cn).equals(normalizedHost)) return true;
        }
        return false;
    }

    private static String stripWildcard(String name) {
        String s = name == null ? "" : name.trim().toLowerCase();
        return s.startsWith("*.") ? s.substring(2) : s;
    }

    public static void validatePrivateKey(String keyPem) {
        if (keyPem == null || keyPem.isBlank()) {
            throw new IllegalArgumentException("私钥内容为空");
        }
        if (LEGACY_RSA_KEY_BLOCK.matcher(keyPem).find()) {
            throw new IllegalArgumentException(
                    "不支持 PKCS#1 格式私钥，请使用 openssl pkcs8 -topk8 -nocrypt 转换为 PKCS#8 (-----BEGIN PRIVATE KEY-----)");
        }
        Matcher m = PKCS8_KEY_BLOCK.matcher(keyPem);
        if (!m.find()) {
            throw new IllegalArgumentException("私钥格式不正确，应为 PKCS#8 PEM（-----BEGIN PRIVATE KEY-----）");
        }
        byte[] der = Base64.getMimeDecoder().decode(m.group(1).trim());
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
        if (!tryKeyFactory("RSA", keySpec) && !tryKeyFactory("EC", keySpec)) {
            throw new IllegalArgumentException("无法解析私钥（既不是 RSA 也不是 EC）");
        }
    }

    private static boolean tryKeyFactory(String algorithm, PKCS8EncodedKeySpec keySpec) {
        try {
            KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            return true;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        }
    }

    @Data
    @AllArgsConstructor
    public static class CertMeta {
        private String subject;
        private LocalDateTime notAfter;
        private List<String> dnsNames;
    }
}
