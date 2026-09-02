package service

import (
	"context"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/pemcert"
	"github.com/wellch4n/oops/server/internal/store"
)

// DomainService manages the domains an installation may route to, and the
// certificates for them.
type DomainService struct {
	store *store.Store
}

// UpsertDomain is the create/update request body.
type UpsertDomain struct {
	Host        string                 `json:"host"`
	Description *string                `json:"description"`
	HTTPS       *bool                  `json:"https"`
	CertMode    *domain.DomainCertMode `json:"certMode"`
	CertPem     *string                `json:"certPem"`
	KeyPem      *string                `json:"keyPem"`
	Environment string                 `json:"environment"`
}

func (s *DomainService) List(ctx context.Context) ([]domain.Domain, error) {
	return s.store.Domains().FindAll(ctx)
}

func (s *DomainService) Get(ctx context.Context, id string) (*domain.Domain, error) {
	record, err := s.store.Domains().FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if record == nil {
		return nil, domain.Bizf("Domain not found: %s", id)
	}
	return record, nil
}

// FindForHost resolves the domain governing a host by longest-suffix match.
func (s *DomainService) FindForHost(ctx context.Context, fullHost string) (*domain.Domain, error) {
	candidates, err := s.store.Domains().FindAll(ctx)
	if err != nil {
		return nil, err
	}
	return domain.FindBestDomainMatch(fullHost, candidates), nil
}

func (s *DomainService) Create(ctx context.Context, request UpsertDomain) (*domain.Domain, error) {
	host := domain.NormalizeHost(&request.Host)
	if err := domain.ValidateHost(host); err != nil {
		return nil, domain.BizWrap(err.Error(), err)
	}
	exists, err := s.store.Domains().ExistsByHost(ctx, host)
	if err != nil {
		return nil, err
	}
	if exists {
		return nil, domain.Bizf("Domain already exists: %s", host)
	}
	environment, err := s.requireValidEnvironment(ctx, request.Environment)
	if err != nil {
		return nil, err
	}
	record := &domain.Domain{
		Host:        &host,
		Description: request.Description,
		Environment: &environment,
	}
	if err := s.applyCertFields(record, request); err != nil {
		return nil, err
	}
	return s.store.Domains().Save(ctx, record)
}

func (s *DomainService) Update(ctx context.Context, id string, request UpsertDomain) (*domain.Domain, error) {
	record, err := s.store.Domains().FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if record == nil {
		return nil, domain.Bizf("Domain not found: %s", id)
	}
	host := domain.NormalizeHost(&request.Host)
	if err := domain.ValidateHost(host); err != nil {
		return nil, domain.BizWrap(err.Error(), err)
	}
	if host != domain.Deref(record.Host) {
		exists, err := s.store.Domains().ExistsByHost(ctx, host)
		if err != nil {
			return nil, err
		}
		if exists {
			return nil, domain.Bizf("Domain already exists: %s", host)
		}
	}
	environment, err := s.requireValidEnvironment(ctx, request.Environment)
	if err != nil {
		return nil, err
	}
	if err := s.rejectRebindingWhileInUse(ctx, record, host, environment); err != nil {
		return nil, err
	}
	record.Host = &host
	record.Description = request.Description
	record.Environment = &environment
	if err := s.applyCertFields(record, request); err != nil {
		return nil, err
	}
	return s.store.Domains().Save(ctx, record)
}

func (s *DomainService) Delete(ctx context.Context, id string) error {
	exists, err := s.store.Domains().ExistsByID(ctx, id)
	if err != nil {
		return err
	}
	if !exists {
		return domain.Bizf("Domain not found: %s", id)
	}
	return s.store.Domains().DeleteByID(ctx, id)
}

func (s *DomainService) requireValidEnvironment(ctx context.Context, name string) (string, error) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return "", domain.Biz("Domain environment is required")
	}
	environment, err := s.store.Environments().FindByName(ctx, trimmed)
	if err != nil {
		return "", err
	}
	if environment == nil {
		return "", domain.Bizf("Environment not found: %s", trimmed)
	}
	return trimmed, nil
}

// rejectRebindingWhileInUse stops a host or environment change that would leave
// an application's configured host governed by nothing — its routes would keep
// working until the next deploy and then quietly lose their certificate.
func (s *DomainService) rejectRebindingWhileInUse(ctx context.Context, record *domain.Domain, newHost, newEnvironment string) error {
	if newHost == domain.Deref(record.Host) && newEnvironment == domain.Deref(record.Environment) {
		return nil
	}
	candidates, err := s.store.Domains().FindAll(ctx)
	if err != nil {
		return err
	}
	serviceConfigs, err := s.store.Applications().FindAllServiceConfigs(ctx)
	if err != nil {
		return err
	}
	for _, serviceConfig := range serviceConfigs {
		for _, environmentConfig := range serviceConfig.EnvironmentConfigs {
			host := domain.Deref(environmentConfig.Host)
			if strings.TrimSpace(host) == "" {
				continue
			}
			governing := domain.FindBestDomainMatch(host, candidates)
			if governing == nil || governing.ID != record.ID {
				continue
			}
			stillCovered := (host == newHost || strings.HasSuffix(host, "."+newHost)) &&
				newEnvironment == domain.Deref(environmentConfig.Environment)
			if !stillCovered {
				return domain.Bizf("Domain is in use by application %s/%s (host %s, environment %s), remove that host first",
					serviceConfig.Namespace, serviceConfig.ApplicationName, host, domain.Deref(environmentConfig.Environment))
			}
		}
	}
	return nil
}

// applyCertFields is where the certificate rules live. Turning HTTPS off clears
// every certificate field rather than leaving the row claiming a policy it no
// longer applies; AUTO clears the uploaded metadata for the same reason.
func (s *DomainService) applyCertFields(record *domain.Domain, request UpsertDomain) error {
	https := request.HTTPS != nil && *request.HTTPS
	record.HTTPS = &https
	if !https {
		clearCertificate(record)
		record.CertMode = nil
		return nil
	}
	if request.CertMode == nil || *request.CertMode == "" {
		return domain.Biz("Certificate mode is required when HTTPS is enabled")
	}
	record.CertMode = request.CertMode
	if *request.CertMode == domain.CertModeAuto {
		clearCertificate(record)
		return nil
	}

	hasCert := strings.TrimSpace(domain.Deref(request.CertPem)) != ""
	hasKey := strings.TrimSpace(domain.Deref(request.KeyPem)) != ""
	if hasCert != hasKey {
		return domain.Biz("Certificate and private key must be provided together")
	}
	if !hasCert {
		// An update that leaves both blank keeps the stored pair; a create has
		// none to keep.
		if strings.TrimSpace(domain.Deref(record.CertPem)) == "" {
			return domain.Biz("UPLOADED mode requires certificate and private key")
		}
		return nil
	}
	meta, err := pemcert.ParseCertificate(domain.Deref(request.CertPem))
	if err != nil {
		return domain.BizWrap(err.Error(), err)
	}
	if err := pemcert.ValidatePrivateKey(domain.Deref(request.KeyPem)); err != nil {
		return domain.BizWrap(err.Error(), err)
	}
	if !pemcert.HostMatches(domain.Deref(record.Host), meta.DNSNames) {
		return domain.Bizf("Certificate does not match domain, certificate is for: %s", strings.Join(meta.DNSNames, ", "))
	}
	record.CertPem = request.CertPem
	record.KeyPem = request.KeyPem
	record.CertSubject = &meta.Subject
	// The certificate's expiry is a real instant, but every timestamp in this
	// product is a naive local wall clock, so it is rendered into the local zone
	// before being stored rather than kept as UTC.
	record.CertNotAfter = domain.LocalTimeOf(meta.NotAfter.Local())
	return nil
}

func clearCertificate(record *domain.Domain) {
	record.CertPem = nil
	record.KeyPem = nil
	record.CertSubject = nil
	record.CertNotAfter = domain.LocalDateTime{}
}
