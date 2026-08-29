package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.DomainRepository;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.routing.Domain;
import com.github.wellch4n.oops.domain.routing.DomainPolicy;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.UpsertDomainCommand;
import com.github.wellch4n.oops.shared.util.PemCertificateParser;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DomainService {

    private final DomainRepository domainRepository;
    private final EnvironmentRepository environmentRepository;
    private final ApplicationRepository applicationRepository;
    private final DomainPolicy domainPolicy;

    public DomainService(DomainRepository domainRepository,
                         EnvironmentRepository environmentRepository,
                         ApplicationRepository applicationRepository,
                         DomainPolicy domainPolicy) {
        this.domainRepository = domainRepository;
        this.environmentRepository = environmentRepository;
        this.applicationRepository = applicationRepository;
        this.domainPolicy = domainPolicy;
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

    public Domain create(UpsertDomainCommand request) {
        String host = domainPolicy.normalizeHost(request.getHost());
        domainPolicy.validateHost(host);
        if (domainRepository.existsByHost(host)) {
            throw new BizException("Domain already exists: " + host);
        }
        String environmentName = requireValidEnvironment(request.getEnvironment());

        Domain domain = new Domain();
        domain.setHost(host);
        domain.setDescription(request.getDescription());
        domain.setEnvironment(environmentName);
        applyCertFields(domain, request);
        return domainRepository.save(domain);
    }

    public Domain update(String id, UpsertDomainCommand request) {
        Optional<Domain> optional = domainRepository.findById(id);
        if (optional.isEmpty()) {
            throw new BizException("Domain not found: " + id);
        }
        Domain domain = optional.get();
        String newHost = domainPolicy.normalizeHost(request.getHost());
        domainPolicy.validateHost(newHost);
        if (!newHost.equals(domain.getHost()) && domainRepository.existsByHost(newHost)) {
            throw new BizException("Domain already exists: " + newHost);
        }
        String environmentName = requireValidEnvironment(request.getEnvironment());
        rejectRebindingWhileInUse(domain, newHost, environmentName);
        domain.setHost(newHost);
        domain.setDescription(request.getDescription());
        domain.setEnvironment(environmentName);
        applyCertFields(domain, request);
        return domainRepository.saveAndFlush(domain);
    }

    private String requireValidEnvironment(String environmentName) {
        if (environmentName == null || environmentName.isBlank()) {
            throw new BizException("Domain environment is required");
        }
        String trimmed = environmentName.trim();
        if (environmentRepository.findFirstByName(trimmed) == null) {
            throw new BizException("Environment not found: " + trimmed);
        }
        return trimmed;
    }

    /**
     * A domain that already governs saved application hosts cannot be moved to another environment
     * (or another suffix) out from under them — the conflict must surface on the admin edit that
     * creates it, not on the application's next innocent deploy.
     */
    private void rejectRebindingWhileInUse(Domain domain, String newHost, String newEnvironmentName) {
        boolean hostUnchanged = newHost.equals(domain.getHost());
        boolean environmentUnchanged = newEnvironmentName.equals(domain.getEnvironment());
        if (hostUnchanged && environmentUnchanged) {
            return;
        }

        List<Domain> candidates = domainRepository.findAll();
        for (ApplicationServiceConfig serviceConfig : applicationRepository.findAllServiceConfigs()) {
            if (serviceConfig.getEnvironmentConfigs() == null) {
                continue;
            }
            for (ApplicationServiceConfig.EnvironmentConfig environmentConfig : serviceConfig.getEnvironmentConfigs()) {
                String host = environmentConfig.getHost();
                if (host == null || host.isBlank()) {
                    continue;
                }
                Domain governing = domainPolicy.findBestMatch(host, candidates, Domain::getHost).orElse(null);
                if (governing == null || !domain.getId().equals(governing.getId())) {
                    continue;
                }
                boolean stillCovered = (host.equals(newHost) || host.endsWith("." + newHost))
                        && newEnvironmentName.equals(environmentConfig.getEnvironment());
                if (!stillCovered) {
                    throw new BizException("Domain is in use by application " + serviceConfig.getNamespace()
                            + "/" + serviceConfig.getApplicationName() + " (host " + host + ", environment "
                            + environmentConfig.getEnvironment() + "), remove that host first");
                }
            }
        }
    }

    public void delete(String id) {
        if (!domainRepository.existsById(id)) {
            throw new BizException("Domain not found: " + id);
        }
        domainRepository.deleteById(id);
    }

    private void applyCertFields(Domain domain, UpsertDomainCommand request) {
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
            } catch (IllegalArgumentException exception) {
                throw new BizException(exception.getMessage(), exception);
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
