package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Domain;
import com.github.wellch4n.oops.data.DomainRepository;
import com.github.wellch4n.oops.enums.DomainCertMode;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.objects.DomainRequest;
import com.github.wellch4n.oops.utils.PemCertificateParser;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DomainService {

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)+$");

    private final DomainRepository domainRepository;

    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public List<Domain> list() {
        return domainRepository.findAll();
    }

    public Domain findForHost(String fullHost) {
        if (fullHost == null) return null;
        String lower = fullHost.trim().toLowerCase();
        if (lower.isEmpty()) return null;
        List<Domain> all = domainRepository.findAll();
        return all.stream()
                .filter(d -> {
                    String h = d.getHost();
                    if (h == null) return false;
                    return lower.equals(h) || lower.endsWith("." + h);
                })
                .max((a, b) -> Integer.compare(a.getHost().length(), b.getHost().length()))
                .orElse(null);
    }

    public Domain get(String id) {
        return domainRepository.findById(id)
                .orElseThrow(() -> new BizException("Domain not found: " + id));
    }

    public Domain create(DomainRequest request) {
        String host = normalizeHost(request.getHost());
        validateHost(host);
        if (domainRepository.existsByHost(host)) {
            throw new BizException("Domain already exists: " + host);
        }

        Domain domain = new Domain();
        domain.setHost(host);
        domain.setDescription(request.getDescription());
        applyCertFields(domain, request);
        return domainRepository.save(domain);
    }

    public Domain update(String id, DomainRequest request) {
        Optional<Domain> optional = domainRepository.findById(id);
        if (optional.isEmpty()) {
            throw new BizException("Domain 不存在: " + id);
        }
        Domain domain = optional.get();
        String newHost = normalizeHost(request.getHost());
        validateHost(newHost);
        if (!newHost.equals(domain.getHost()) && domainRepository.existsByHost(newHost)) {
            throw new BizException("域名已存在: " + newHost);
        }
        domain.setHost(newHost);
        domain.setDescription(request.getDescription());
        applyCertFields(domain, request);
        return domainRepository.saveAndFlush(domain);
    }

    public void delete(String id) {
        if (!domainRepository.existsById(id)) {
            throw new BizException("Domain 不存在: " + id);
        }
        domainRepository.deleteById(id);
    }

    private void applyCertFields(Domain domain, DomainRequest request) {
        boolean https = Boolean.TRUE.equals(request.getHttps());
        domain.setHttps(https);

        if (!https) {
            domain.setCertMode(null);
            domain.setCertPem(null);
            domain.setKeyPem(null);
            domain.setCertSubject(null);
            domain.setCertNotAfter(null);
            return;
        }

        DomainCertMode mode = request.getCertMode();
        if (mode == null) {
            throw new BizException("Certificate mode is required when HTTPS is enabled");
        }
        domain.setCertMode(mode);

        if (mode == DomainCertMode.AUTO) {
            domain.setCertPem(null);
            domain.setKeyPem(null);
            domain.setCertSubject(null);
            domain.setCertNotAfter(null);
            return;
        }

        // UPLOADED
        boolean hasNewCert = request.getCertPem() != null && !request.getCertPem().isBlank();
        boolean hasNewKey = request.getKeyPem() != null && !request.getKeyPem().isBlank();

        if (hasNewCert != hasNewKey) {
            throw new BizException("Certificate and private key must be provided together");
        }

        if (hasNewCert) {
            PemCertificateParser.CertMeta meta;
            try {
                meta = PemCertificateParser.parseCertificate(request.getCertPem());
                PemCertificateParser.validatePrivateKey(request.getKeyPem());
            } catch (IllegalArgumentException e) {
                throw new BizException(e.getMessage(), e);
            }
            if (!PemCertificateParser.hostMatches(domain.getHost(), meta.getDnsNames())) {
                throw new BizException("Certificate does not match domain, certificate is for: "
                        + String.join(", ", meta.getDnsNames()));
            }
            domain.setCertPem(request.getCertPem());
            domain.setKeyPem(request.getKeyPem());
            domain.setCertSubject(meta.getSubject());
            domain.setCertNotAfter(meta.getNotAfter());
        } else if (domain.getCertPem() == null || domain.getCertPem().isBlank()) {
            throw new BizException("UPLOADED mode requires certificate and private key");
        }
    }

    private String normalizeHost(String host) {
        if (host == null) return "";
        String trimmed = host.trim().toLowerCase();
        if (trimmed.startsWith("*.")) {
            trimmed = trimmed.substring(2);
        }
        return trimmed;
    }

    private void validateHost(String host) {
        if (host.isEmpty()) {
            throw new BizException("Domain host is required");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new BizException("Invalid domain format: " + host);
        }
    }
}
