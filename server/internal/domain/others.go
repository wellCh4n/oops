package domain

import (
	"regexp"
	"strings"
)

// ---------------------------------------------------------------------------
// Environment

type KubernetesApiServer struct {
	URL   *string `json:"url"`
	Token *string `json:"token"`
}

type ImageRepository struct {
	URL      *string `json:"url"`
	Username *string `json:"username"`
	Password *string `json:"password"`
}

// HasCredentials = none of url/username/password empty.
func (r *ImageRepository) HasCredentials() bool {
	return r != nil && Deref(r.URL) != "" && Deref(r.Username) != "" && Deref(r.Password) != ""
}

type GitCredential struct {
	Username   *string `json:"username"`
	Password   *string `json:"password"`
	PrivateKey *string `json:"privateKey"`
}

// IsEmpty = all three blank.
func (g *GitCredential) IsEmpty() bool {
	return g == nil || (isBlankPtr(g.Username) && isBlankPtr(g.Password) && isBlankPtr(g.PrivateKey))
}

type Environment struct {
	ID                  string
	Name                string
	KubernetesApiServer *KubernetesApiServer
	WorkNamespace       *string
	BuildStorageClass   *string
	ImageRepository     *ImageRepository
	GitCredential       *GitCredential
}

// ---------------------------------------------------------------------------
// Identity

// User is serialised to the API as-is, password hash and access token
// included. That is an exposure — both reach every authenticated caller of
// /api/users — and tests/integration/test_user.py documents it deliberately,
// failing the day a response DTO closes it.
type User struct {
	ID          string        `json:"id"`
	CreatedTime LocalDateTime `json:"createdTime"`
	Username    *string       `json:"username"`
	Email       *string       `json:"email"`
	Password    *string       `json:"password"`
	Role        *UserRole     `json:"role"`
	AccessToken *string       `json:"accessToken"`
	Enabled     *bool         `json:"enabled"`
}

// IsEnabled treats nil as enabled.
func (u *User) IsEnabled() bool { return u.Enabled == nil || *u.Enabled }

// RoleName returns the role name or "USER" when null.
func (u *User) RoleName() string {
	if u.Role == nil || *u.Role == "" {
		return string(RoleUser)
	}
	return string(*u.Role)
}

func (u *User) ToOperator() *Operator {
	if u == nil {
		return nil
	}
	role := RoleUser
	if u.Role != nil {
		role = *u.Role
	}
	return &Operator{UserID: u.ID, Role: role, Enabled: u.IsEnabled()}
}

type ExternalAccount struct {
	ID             string
	CreatedTime    LocalDateTime
	Email          *string
	Provider       ExternalAccountProvider
	ProviderUserID *string
	UserID         *string
}

// ---------------------------------------------------------------------------
// Namespace

type Namespace struct {
	ID          string        `json:"id"`
	CreatedTime LocalDateTime `json:"createdTime"`
	Name        *string       `json:"name"`
	Description *string       `json:"description"`
}

// ---------------------------------------------------------------------------
// Routing (domains)

type Domain struct {
	ID           string
	CreatedTime  LocalDateTime
	Host         *string
	Description  *string
	HTTPS        *bool
	CertMode     *DomainCertMode
	CertPem      *string
	KeyPem       *string
	CertSubject  *string
	CertNotAfter LocalDateTime
	Environment  *string
}

// AllowsEnvironment = environment != nil && equals.
func (d *Domain) AllowsEnvironment(environment string) bool {
	return d != nil && d.Environment != nil && *d.Environment == environment
}

var domainHostPattern = regexp.MustCompile(`^([a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)(\.[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)+$`)

// NormalizeHost: nil -> "", trim, strip leading "*.".
func NormalizeHost(host *string) string {
	if host == nil {
		return ""
	}
	h := strings.TrimSpace(*host)
	return strings.TrimPrefix(h, "*.")
}

// ValidateHost mirrors DomainPolicy.validateHost.
func ValidateHost(host string) error {
	if strings.TrimSpace(host) == "" {
		return Biz("Domain host is required")
	}
	if host != strings.ToLower(host) {
		return Bizf("Domain must be lowercase: %s", host)
	}
	if !domainHostPattern.MatchString(host) {
		return Bizf("Invalid domain format: %s", host)
	}
	return nil
}

// FindBestDomainMatch returns the domain whose host equals fullHost or is a
// dot-suffix of it, preferring the longest host. nil when none.
func FindBestDomainMatch(fullHost string, candidates []Domain) *Domain {
	lower := strings.ToLower(strings.TrimSpace(fullHost))
	if lower == "" {
		return nil
	}
	var best *Domain
	for i := range candidates {
		d := &candidates[i]
		if d.Host == nil {
			continue
		}
		h := *d.Host
		if lower == h || strings.HasSuffix(lower, "."+h) {
			if best == nil || len(h) > len(*best.Host) {
				best = d
			}
		}
	}
	return best
}

// ---------------------------------------------------------------------------
// Resource names

var (
	resourceNamePattern    = regexp.MustCompile(`^[a-z]([-a-z0-9]*[a-z0-9])?$`)
	environmentNamePattern = regexp.MustCompile(`^[A-Za-z]([-A-Za-z0-9]*[A-Za-z0-9])?$`)
)

const MaxResourceNameLength = 24

// CheckResourceName validates a name Kubernetes will have to accept as an
// object name. The error states the rule: this is a name a person typed, and
// telling them only that something went wrong leaves them guessing.
func CheckResourceName(name string) error {
	if strings.TrimSpace(name) == "" {
		return Biz("Name cannot be empty")
	}
	if len(name) > MaxResourceNameLength {
		return Biz("Name cannot exceed 24 characters")
	}
	if !resourceNamePattern.MatchString(name) {
		return Biz("Name must contain only lowercase letters, digits, and hyphens, start with a lowercase letter, and end with a letter or digit")
	}
	return nil
}

// CheckEnvironmentName is CheckResourceName's looser sibling: an environment
// name is a label in this product, not a Kubernetes object name, so it may
// carry capitals.
func CheckEnvironmentName(name string) error {
	if strings.TrimSpace(name) == "" {
		return Biz("Name cannot be empty")
	}
	if len(name) > MaxResourceNameLength {
		return Biz("Name cannot exceed 24 characters")
	}
	if !environmentNamePattern.MatchString(name) {
		return Biz("Name must contain only letters, digits, and hyphens, start with a letter, and end with a letter or digit")
	}
	return nil
}

// ToResourceName mirrors ConfigMapItem.toResourceName.
func ToResourceName(key string) string {
	name := strings.ToLower(key)
	name = regexp.MustCompile(`[^a-z0-9]+`).ReplaceAllString(name, "-")
	name = strings.Trim(name, "-")
	if len(name) > 50 {
		name = strings.TrimRight(name[:50], "-")
	}
	if name == "" {
		return "item"
	}
	return name
}

// ---------------------------------------------------------------------------
// Sandbox

type SandboxInstance struct {
	ID            string                `json:"id"`
	Name          *string               `json:"name"`
	Environment   string                `json:"environment"`
	Image         *string               `json:"image"`
	Status        SandboxInstanceStatus `json:"status"`
	CreatedBy     *string               `json:"createdBy"`
	CreatedByName *string               `json:"createdByName"`
	CreatedAt     *string               `json:"createdAt"` // RFC3339 instant (UTC)
	CPURequest    *string               `json:"cpuRequest"`
	CPULimit      *string               `json:"cpuLimit"`
	MemoryRequest *string               `json:"memoryRequest"`
	MemoryLimit   *string               `json:"memoryLimit"`
}

// ---------------------------------------------------------------------------
// Resource alerts

// AlertMetric is the resource an alert watches. The names are the strings the
// `metric` column stores.
type AlertMetric string

const (
	AlertMetricCPU    AlertMetric = "CPU"
	AlertMetricMemory AlertMetric = "MEMORY"
)

// AlertState is one application+environment+metric's alert state. It makes the
// scan job edge-triggered: notify on OK->FIRING, repeat on an interval, notify
// once on recovery.
type AlertState struct {
	ID               string
	CreatedTime      LocalDateTime
	Namespace        string
	ApplicationName  string
	Environment      string
	Metric           AlertMetric
	Firing           bool
	FiringSince      LocalDateTime
	LastNotifiedTime LocalDateTime
}
