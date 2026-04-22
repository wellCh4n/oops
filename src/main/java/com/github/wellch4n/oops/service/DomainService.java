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
            "^(\\*\\.)?([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)+$");

    private final DomainRepository domainRepository;

    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public List<Domain> list() {
        return domainRepository.findAll();
    }

    public Domain get(String id) {
        return domainRepository.findById(id)
                .orElseThrow(() -> new BizException("Domain 不存在: " + id));
    }

    public Domain create(DomainRequest request) {
        String host = normalizeHost(request.getHost());
        validateHost(host);
        if (domainRepository.existsByHost(host)) {
            throw new BizException("域名已存在: " + host);
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
            throw new BizException("启用 HTTPS 时必须指定证书模式");
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
            throw new BizException("证书和私钥必须同时提供");
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
                throw new BizException("证书与域名不匹配，证书适用于: "
                        + String.join(", ", meta.getDnsNames()));
            }
            domain.setCertPem(request.getCertPem());
            domain.setKeyPem(request.getKeyPem());
            domain.setCertSubject(meta.getSubject());
            domain.setCertNotAfter(meta.getNotAfter());
        } else if (domain.getCertPem() == null || domain.getCertPem().isBlank()) {
            throw new BizException("UPLOADED 模式需要上传证书和私钥");
        }
    }

    private String normalizeHost(String host) {
        if (host == null) return "";
        return host.trim().toLowerCase();
    }

    private void validateHost(String host) {
        if (host.isEmpty()) {
            throw new BizException("域名不能为空");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new BizException("域名格式不正确：" + host);
        }
    }
}
