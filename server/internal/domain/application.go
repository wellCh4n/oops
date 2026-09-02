package domain

import (
	"strings"
	"unicode/utf8"
)

// Application is the aggregate root. Child configs are nil when the
// corresponding row does not exist.
type Application struct {
	ID            string
	CreatedTime   LocalDateTime
	Name          string
	Description   *string
	Icon          *string
	Namespace     string
	Owner         *string
	BuildConfig   *ApplicationBuildConfig
	RuntimeSpec   *ApplicationRuntimeSpec
	ServiceConfig *ApplicationServiceConfig
	ExpertConfig  *ApplicationExpertConfig
	Environments  []ApplicationEnvironment
	Collaborators []ApplicationCollaborator
}

type ApplicationEnvironment struct {
	ID              string
	CreatedTime     LocalDateTime
	Namespace       string
	ApplicationName string
	Environment     string
}

type ApplicationCollaborator struct {
	ID              string
	CreatedTime     LocalDateTime
	Namespace       string
	ApplicationName string
	UserID          string
}

// ---------------------------------------------------------------------------
// Build config

// SourceConfig is polymorphic on "type": {"type":"GIT","repository":"..."} or {"type":"ZIP"}.
type SourceConfig struct {
	Type       ApplicationSourceType `json:"type"`
	Repository *string               `json:"repository,omitempty"`
}

type DockerFileConfig struct {
	Type    DockerFileType `json:"type"`
	Path    *string        `json:"path"`
	Content *string        `json:"content"`
}

type BuildEnvironmentConfig struct {
	Environment  *string `json:"environment"`
	BuildCommand *string `json:"buildCommand"`
}

type ApplicationBuildConfig struct {
	ID                 string
	CreatedTime        LocalDateTime
	Namespace          string
	ApplicationName    string
	SourceType         *ApplicationSourceType
	SourceConfig       *SourceConfig
	DockerFileConfig   *DockerFileConfig
	BuildImage         *string
	EnvironmentConfigs []BuildEnvironmentConfig // nil = column NULL
}

// Repository returns the git repository or nil for ZIP / unset.
func (c *ApplicationBuildConfig) Repository() *string {
	if c == nil || c.SourceConfig == nil || c.SourceConfig.Type == SourceZip {
		return nil
	}
	return c.SourceConfig.Repository
}

// EffectiveSourceType treats a NULL source type as GIT.
func (c *ApplicationBuildConfig) EffectiveSourceType() ApplicationSourceType {
	if c == nil || c.SourceType == nil || *c.SourceType == "" {
		return SourceGit
	}
	return *c.SourceType
}

// SourceType of the application: buildConfig.sourceType or GIT.
func (a *Application) SourceType() ApplicationSourceType {
	if a == nil {
		return SourceGit
	}
	return a.BuildConfig.EffectiveSourceType()
}

// ValidateBuildConfig applies ApplicationBuildConfigPolicy.validate.
func ValidateBuildConfig(sourceType ApplicationSourceType, repository *string, dockerFile *DockerFileConfig) error {
	if sourceType == SourceGit && isBlankPtr(repository) {
		return Biz("Repository is required when source type is GIT")
	}
	if dockerFile != nil && dockerFile.Type == DockerFileUser && isBlankPtr(dockerFile.Content) {
		return Biz("Dockerfile content is required when type is USER")
	}
	return nil
}

// BuildSourceConfig mirrors ApplicationBuildConfigPolicy.buildSourceConfig.
func BuildSourceConfig(sourceType ApplicationSourceType, repository *string) *SourceConfig {
	if sourceType == SourceZip {
		return &SourceConfig{Type: SourceZip}
	}
	return &SourceConfig{Type: SourceGit, Repository: repository}
}

// ---------------------------------------------------------------------------
// Runtime spec

type RuntimeEnvironmentConfig struct {
	Environment   *string `json:"environment"`
	CPURequest    *string `json:"cpuRequest"`
	CPULimit      *string `json:"cpuLimit"`
	MemoryRequest *string `json:"memoryRequest"`
	MemoryLimit   *string `json:"memoryLimit"`
	Replicas      *int    `json:"replicas"`
}

type Probe struct {
	Enabled             *bool   `json:"enabled"`
	Path                *string `json:"path"`
	InitialDelaySeconds *int    `json:"initialDelaySeconds"`
	PeriodSeconds       *int    `json:"periodSeconds"`
	TimeoutSeconds      *int    `json:"timeoutSeconds"`
	FailureThreshold    *int    `json:"failureThreshold"`
}

const (
	DefaultProbePath             = "/"
	DefaultProbeInitialDelay     = 30
	DefaultProbePeriod           = 10
	DefaultProbeTimeout          = 3
	DefaultProbeFailureThreshold = 3
)

// DefaultProbe returns the Java field-initialiser defaults.
func DefaultProbe() Probe {
	return Probe{
		Enabled:             ptr(false),
		Path:                ptr(DefaultProbePath),
		InitialDelaySeconds: ptr(DefaultProbeInitialDelay),
		PeriodSeconds:       ptr(DefaultProbePeriod),
		TimeoutSeconds:      ptr(DefaultProbeTimeout),
		FailureThreshold:    ptr(DefaultProbeFailureThreshold),
	}
}

// ProbeEnabled = enabled == TRUE && path non-blank.
func (p Probe) ProbeEnabled() bool {
	return p.Enabled != nil && *p.Enabled && !isBlankPtr(p.Path)
}

// NormalizedPath: blank -> "/", else ensure leading "/".
func (p Probe) NormalizedPath() string {
	if isBlankPtr(p.Path) {
		return "/"
	}
	path := strings.TrimSpace(*p.Path)
	if !strings.HasPrefix(path, "/") {
		return "/" + path
	}
	return path
}

func (p Probe) EffectiveInitialDelay() int {
	if p.InitialDelaySeconds != nil && *p.InitialDelaySeconds >= 0 {
		return *p.InitialDelaySeconds
	}
	return DefaultProbeInitialDelay
}
func (p Probe) EffectivePeriod() int {
	if p.PeriodSeconds != nil && *p.PeriodSeconds > 0 {
		return *p.PeriodSeconds
	}
	return DefaultProbePeriod
}
func (p Probe) EffectiveTimeout() int {
	if p.TimeoutSeconds != nil && *p.TimeoutSeconds > 0 {
		return *p.TimeoutSeconds
	}
	return DefaultProbeTimeout
}
func (p Probe) EffectiveFailureThreshold() int {
	if p.FailureThreshold != nil && *p.FailureThreshold > 0 {
		return *p.FailureThreshold
	}
	return DefaultProbeFailureThreshold
}

type HealthCheck struct {
	Liveness  *Probe `json:"liveness"`
	Readiness *Probe `json:"readiness"`
}

// DefaultHealthCheck has both probes disabled with default values.
func DefaultHealthCheck() *HealthCheck {
	live, ready := DefaultProbe(), DefaultProbe()
	return &HealthCheck{Liveness: &live, Readiness: &ready}
}

func (h *HealthCheck) LivenessOrDefault() Probe {
	if h == nil || h.Liveness == nil {
		return DefaultProbe()
	}
	return *h.Liveness
}
func (h *HealthCheck) ReadinessOrDefault() Probe {
	if h == nil || h.Readiness == nil {
		return DefaultProbe()
	}
	return *h.Readiness
}

// NormalizeHealthCheck mirrors HealthCheckPolicy.normalize.
func NormalizeHealthCheck(hc *HealthCheck) (*HealthCheck, error) {
	if hc == nil {
		hc = &HealthCheck{}
	}
	live, err := normalizeProbe(hc.Liveness)
	if err != nil {
		return nil, err
	}
	ready, err := normalizeProbe(hc.Readiness)
	if err != nil {
		return nil, err
	}
	return &HealthCheck{Liveness: live, Readiness: ready}, nil
}

func normalizeProbe(p *Probe) (*Probe, error) {
	if p == nil {
		d := DefaultProbe()
		return &d, nil
	}
	enabled := p.Enabled != nil && *p.Enabled
	var path string
	if isBlankPtr(p.Path) {
		if enabled {
			return nil, Biz("Health check path is required")
		}
		path = "/"
	} else {
		path = strings.TrimSpace(*p.Path)
		if !strings.HasPrefix(path, "/") {
			path = "/" + path
		}
	}
	out := Probe{
		Enabled:             ptr(enabled),
		Path:                ptr(path),
		InitialDelaySeconds: ptr(DefaultProbeInitialDelay),
		PeriodSeconds:       ptr(DefaultProbePeriod),
		TimeoutSeconds:      ptr(DefaultProbeTimeout),
		FailureThreshold:    ptr(DefaultProbeFailureThreshold),
	}
	if p.InitialDelaySeconds != nil && *p.InitialDelaySeconds >= 0 {
		out.InitialDelaySeconds = ptr(*p.InitialDelaySeconds)
	}
	if p.PeriodSeconds != nil && *p.PeriodSeconds > 0 {
		out.PeriodSeconds = ptr(*p.PeriodSeconds)
	}
	if p.TimeoutSeconds != nil && *p.TimeoutSeconds > 0 {
		out.TimeoutSeconds = ptr(*p.TimeoutSeconds)
	}
	if p.FailureThreshold != nil && *p.FailureThreshold > 0 {
		out.FailureThreshold = ptr(*p.FailureThreshold)
	}
	return &out, nil
}

type ApplicationRuntimeSpec struct {
	ID                 string
	CreatedTime        LocalDateTime
	Namespace          string
	ApplicationName    string
	EnvironmentConfigs []RuntimeEnvironmentConfig // nil = NULL
	HealthCheck        *HealthCheck
}

// RuntimeEnvironmentConfigOrDefault returns the config for env, or an empty one.
func (a *Application) RuntimeEnvironmentConfigOrDefault(environment string) RuntimeEnvironmentConfig {
	if a != nil && a.RuntimeSpec != nil {
		for _, c := range a.RuntimeSpec.EnvironmentConfigs {
			if c.Environment != nil && *c.Environment == environment {
				return c
			}
		}
	}
	return RuntimeEnvironmentConfig{}
}

// HealthCheckOrDefault returns the stored health check or a fresh default.
func (a *Application) HealthCheckOrDefault() *HealthCheck {
	if a != nil && a.RuntimeSpec != nil && a.RuntimeSpec.HealthCheck != nil {
		return a.RuntimeSpec.HealthCheck
	}
	return DefaultHealthCheck()
}

// ---------------------------------------------------------------------------
// Service config

type ServiceEnvironmentConfig struct {
	Environment           *string `json:"environment"`
	Host                  *string `json:"host"`
	HTTPS                 *bool   `json:"https"`
	BasicAuthEnabled      *bool   `json:"basicAuthEnabled,omitempty"`
	BasicAuthUsername     *string `json:"basicAuthUsername,omitempty"`
	BasicAuthPasswordHash *string `json:"basicAuthPasswordHash,omitempty"`
}

func (c ServiceEnvironmentConfig) BasicAuthConfigured() bool {
	return c.BasicAuthEnabled != nil && *c.BasicAuthEnabled &&
		!isBlankPtr(c.BasicAuthUsername) && !isBlankPtr(c.BasicAuthPasswordHash)
}

type ApplicationServiceConfig struct {
	ID                 string
	CreatedTime        LocalDateTime
	Namespace          string
	ApplicationName    string
	Port               *int
	InternalPorts      []int // nil = NULL
	EnvironmentConfigs []ServiceEnvironmentConfig
}

// EnvironmentConfigsFor filters configs by environment name.
func (c *ApplicationServiceConfig) EnvironmentConfigsFor(environment string) []ServiceEnvironmentConfig {
	out := []ServiceEnvironmentConfig{}
	if c == nil {
		return out
	}
	for _, cfg := range c.EnvironmentConfigs {
		if cfg.Environment != nil && *cfg.Environment == environment {
			out = append(out, cfg)
		}
	}
	return out
}

// DistinctInternalPorts returns ports > 0, de-duplicated, order preserved.
func (c *ApplicationServiceConfig) DistinctInternalPorts() []int {
	out := []int{}
	if c == nil {
		return out
	}
	seen := map[int]bool{}
	for _, p := range c.InternalPorts {
		if p > 0 && !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	return out
}

// ServiceConfigOrDefault returns the stored config or an empty one with
// environmentConfigs = [].
func (a *Application) ServiceConfigOrDefault() *ApplicationServiceConfig {
	if a != nil && a.ServiceConfig != nil {
		cfg := *a.ServiceConfig
		if cfg.EnvironmentConfigs == nil {
			cfg.EnvironmentConfigs = []ServiceEnvironmentConfig{}
		}
		return &cfg
	}
	return &ApplicationServiceConfig{Namespace: a.Namespace, ApplicationName: a.Name, EnvironmentConfigs: []ServiceEnvironmentConfig{}}
}

// ---------------------------------------------------------------------------
// Expert config

type ExpertEnvironmentConfig struct {
	Environment             *string  `json:"environment"`
	ServiceAccountName      *string  `json:"serviceAccountName"`
	Priority                *string  `json:"priority"`
	ScheduledRestartEnabled bool     `json:"scheduledRestartEnabled"`
	ScheduledRestartCron    *string  `json:"scheduledRestartCron"`
	NodeNames               []string `json:"nodeNames"`
}

type ApplicationExpertConfig struct {
	ID                 string
	CreatedTime        LocalDateTime
	Namespace          string
	ApplicationName    string
	EnvironmentConfigs []ExpertEnvironmentConfig
}

func (a *Application) ExpertEnvironmentConfigOrDefault(environment string) ExpertEnvironmentConfig {
	if a != nil && a.ExpertConfig != nil {
		for _, c := range a.ExpertConfig.EnvironmentConfigs {
			if c.Environment != nil && *c.Environment == environment {
				return c
			}
		}
	}
	return ExpertEnvironmentConfig{}
}

// PriorityFromValue: blank/unknown -> NORMAL (trim + upper-case).
func PriorityFromValue(value *string) ApplicationPriority {
	if value == nil {
		return PriorityNormal
	}
	switch strings.ToUpper(strings.TrimSpace(*value)) {
	case "HIGH":
		return PriorityHigh
	case "LOW":
		return PriorityLow
	default:
		return PriorityNormal
	}
}

// PriorityClassName returns the PriorityClass name or "" for NORMAL.
func (p ApplicationPriority) PriorityClassName() string {
	switch p {
	case PriorityHigh:
		return "oops-high-priority"
	case PriorityLow:
		return "oops-low-priority"
	default:
		return ""
	}
}

// PriorityValue returns the PriorityClass value.
func (p ApplicationPriority) PriorityValue() int32 {
	switch p {
	case PriorityHigh:
		return 1_000_000
	case PriorityLow:
		return -1_000_000
	default:
		return 0
	}
}

// NormalizeNodeNames: nil->[], blank dropped, distinct, sorted (for change detection).
func NormalizeNodeNames(names []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, n := range names {
		n = strings.TrimSpace(n)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	sortStrings(out)
	return out
}

// ---------------------------------------------------------------------------
// Profile rules

// NormalizeIcon mirrors Application.changeIcon: blank -> nil; > 8 code points
// or any ASCII code point -> error.
func NormalizeIcon(icon *string) (*string, error) {
	if icon == nil || strings.TrimSpace(*icon) == "" {
		return nil, nil
	}
	trimmed := strings.TrimSpace(*icon)
	if utf8.RuneCountInString(trimmed) > 8 {
		return nil, Biz("Application icon must be a single emoji")
	}
	for _, r := range trimmed {
		if r < 0x80 {
			return nil, Biz("Application icon must be a single emoji")
		}
	}
	return &trimmed, nil
}

// NormalizeCollaboratorIDs drops blanks and the owner, de-duplicates preserving order.
func NormalizeCollaboratorIDs(userIDs []string, owner *string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, id := range userIDs {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		if owner != nil && *owner == id {
			continue
		}
		seen[id] = true
		out = append(out, id)
	}
	return out
}

// CollaboratorUserIDs returns non-blank collaborator ids.
func (a *Application) CollaboratorUserIDs() []string {
	out := []string{}
	if a == nil {
		return out
	}
	for _, c := range a.Collaborators {
		if strings.TrimSpace(c.UserID) != "" {
			out = append(out, c.UserID)
		}
	}
	return out
}

// Operator is the caller of an operation.
type Operator struct {
	UserID  string
	Role    UserRole
	Enabled bool
}

func (o Operator) IsAdmin() bool { return o.Role == RoleAdmin }

// EnsureCanOperate mirrors ApplicationAccessPolicy.ensureCanOperate.
func EnsureCanOperate(app *Application, operator *Operator) error {
	if app == nil {
		return Biz("Application not found")
	}
	if operator == nil || !operator.Enabled {
		return Biz("Permission denied")
	}
	if operator.IsAdmin() {
		return nil
	}
	if operator.UserID != "" {
		if app.Owner != nil && *app.Owner == operator.UserID {
			return nil
		}
		for _, id := range app.CollaboratorUserIDs() {
			if id == operator.UserID {
				return nil
			}
		}
	}
	return Biz("Permission denied")
}
