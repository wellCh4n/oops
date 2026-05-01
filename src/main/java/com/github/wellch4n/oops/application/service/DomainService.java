package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.DomainRepository;
import com.github.wellch4n.oops.domain.routing.Domain;
import com.github.wellch4n.oops.domain.routing.DomainPolicy;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.interfaces.dto.DomainRequest;
import com.github.wellch4n.oops.shared.util.PemCertificateParser;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DomainService {

    private final DomainRepository domainRepository;
    private final DomainPolicy domainPolicy = new DomainPolicy();

    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public List<Domain> list() {
        return domainRepository.findAll();
    }

    public Domain findForHost(String fullHost) {
        return domainPolicy.findBestMatch(fullHost, domainRepository.findAll(), Domain::getHost)
                .orElse(null);
    }

    public Domain get(String id) {
        return domainRepository.findById(id)
                .orElseThrow(() -> new BizException("Domain not found: " + id));
    }

    public Domain create(DomainRequest request) {
        String host = domainPolicy.normalizeHost(request.getHost());
        domainPolicy.validateHost(host);
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
        String newHost = domainPolicy.normalizeHost(request.getHost());
        domainPolicy.validateHost(newHost);
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

}
