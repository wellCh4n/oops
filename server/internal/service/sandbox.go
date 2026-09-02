package service

import (
	"context"
	"regexp"
	"sort"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s/sandbox"
)

// Sandbox defaults. A request may override any of them; these are what a caller
// gets for leaving the field out.
const (
	defaultTimeoutSeconds = 300
	defaultTTLSeconds     = 60
	defaultCPURequest     = "100m"
	defaultCPULimit       = "1"
	defaultMemoryRequest  = "128Mi"
	defaultMemoryLimit    = "512Mi"
	// sandboxContainer is what the pod's container is called, for the file and
	// terminal endpoints that reach into it.
	sandboxContainer = "sandbox"
)

// envVarName is what a shell can actually export.
var envVarName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// SandboxService runs arbitrary commands in isolated Kubernetes workloads:
// one-shot executions as Jobs, and long-lived instances as StatefulSets that
// also expose a terminal and a filesystem.
type SandboxService struct {
	services *Services
}

// ResourceSpec is the request/limit pair a caller may set per resource.
type ResourceSpec struct {
	Request *string `json:"request"`
	Limit   *string `json:"limit"`
}

// ExecutionRequest is a one-shot run. The commands are joined into a single
// script, so they share one shell — a variable set by one is visible to the next.
type ExecutionRequest struct {
	Environment             string            `json:"environment"`
	Image                   string            `json:"image"`
	Commands                []string          `json:"commands"`
	TimeoutSeconds          *int              `json:"timeoutSeconds"`
	TTLSecondsAfterFinished *int              `json:"ttlSecondsAfterFinished"`
	CPU                     *ResourceSpec     `json:"cpu"`
	Memory                  *ResourceSpec     `json:"memory"`
	Env                     map[string]string `json:"env"`
	Stream                  *bool             `json:"stream"`
}

// InstanceRequest creates a long-lived instance.
type InstanceRequest struct {
	Environment         string            `json:"environment"`
	Name                string            `json:"name"`
	Image               string            `json:"image"`
	CPU                 *ResourceSpec     `json:"cpu"`
	Memory              *ResourceSpec     `json:"memory"`
	Env                 map[string]string `json:"env"`
	UseDefaultKeepalive *bool             `json:"useDefaultKeepalive"`
}

// InstanceExecRequest runs a command in an existing instance. It carries no
// environment: the instance is found by id across every environment.
type InstanceExecRequest struct {
	Command        string `json:"command"`
	TimeoutSeconds *int   `json:"timeoutSeconds"`
	Stream         *bool  `json:"stream"`
}

// Images lists the runtime images the create form offers, in the order it will
// render them.
//
// The list is advisory, not an allowlist: nothing on the execution path checks a
// request against it, so any image the cluster can pull will run. Enforcing it
// would break every caller that passes an image of its own, so it is a change to
// make deliberately rather than a check to add here.
func (s *SandboxService) Images() []string {
	images := s.services.Config.Sandbox.Images
	if len(images) == 0 {
		images = sandbox.BuiltinRuntimes()
	}
	sorted := append([]string(nil), images...)
	sort.Strings(sorted)
	return sorted
}

// ---------------------------------------------------------------------------
// request validation

func trimToEmpty(value string) string { return strings.TrimSpace(value) }

// sanitizeEnv refuses a name no shell could export rather than silently
// dropping it, which would leave a script reading an empty variable and no
// explanation.
func sanitizeEnv(env map[string]string) ([]sandbox.EnvVar, error) {
	names := make([]string, 0, len(env))
	for name := range env {
		if strings.TrimSpace(name) == "" {
			continue
		}
		names = append(names, name)
	}
	sort.Strings(names)
	vars := make([]sandbox.EnvVar, 0, len(names))
	for _, raw := range names {
		name := strings.TrimSpace(raw)
		if !envVarName.MatchString(name) {
			return nil, domain.Bizf("Invalid env var name: %s (must match [A-Za-z_][A-Za-z0-9_]*)", name)
		}
		vars = append(vars, sandbox.EnvVar{Name: name, Value: env[raw]})
	}
	return vars, nil
}

func positiveOrDefault(requested *int, fallback int, field string) (int, error) {
	if requested == nil {
		return fallback, nil
	}
	if *requested <= 0 {
		return 0, domain.Bizf("%s must be positive", field)
	}
	return *requested, nil
}

func nonNegativeOrDefault(requested *int, fallback int, field string) (int, error) {
	if requested == nil {
		return fallback, nil
	}
	if *requested < 0 {
		return 0, domain.Bizf("%s must be non-negative", field)
	}
	return *requested, nil
}

func firstNonBlank(requested *string, fallback string) string {
	if requested != nil && strings.TrimSpace(*requested) != "" {
		return strings.TrimSpace(*requested)
	}
	return fallback
}

func (r *ResourceSpec) requestOr(fallback string) string {
	if r == nil {
		return fallback
	}
	return firstNonBlank(r.Request, fallback)
}

func (r *ResourceSpec) limitOr(fallback string) string {
	if r == nil {
		return fallback
	}
	return firstNonBlank(r.Limit, fallback)
}

// ---------------------------------------------------------------------------
// one-shot executions

// Execute runs a script to completion and returns its combined output.
func (s *SandboxService) Execute(ctx context.Context, request ExecutionRequest, operatorUserID string) (sandbox.ExecResult, error) {
	spec, environment, err := s.jobSpec(ctx, request, operatorUserID)
	if err != nil {
		return sandbox.ExecResult{}, err
	}
	return s.services.Sandbox.Execute(ctx, environment.KubernetesApiServer, domain.Deref(environment.WorkNamespace), spec)
}

// Stream runs a script and reports its output line by line.
func (s *SandboxService) Stream(ctx context.Context, request ExecutionRequest, operatorUserID string, onLine func(string), onExit func(int)) error {
	spec, environment, err := s.jobSpec(ctx, request, operatorUserID)
	if err != nil {
		return err
	}
	return s.services.Sandbox.Stream(ctx, environment.KubernetesApiServer, domain.Deref(environment.WorkNamespace), spec, onLine, onExit)
}

// jobSpec validates the whole request before anything reaches the cluster:
// scheduling first and failing afterwards would leave pods behind for requests
// that were never going to be accepted.
func (s *SandboxService) jobSpec(ctx context.Context, request ExecutionRequest, operatorUserID string) (sandbox.JobSpec, *domain.Environment, error) {
	var empty sandbox.JobSpec
	if trimToEmpty(request.Environment) == "" {
		return empty, nil, domain.Biz("environment is required")
	}
	image := trimToEmpty(request.Image)
	if image == "" {
		return empty, nil, domain.Biz("image is required")
	}
	if len(request.Commands) == 0 {
		return empty, nil, domain.Biz("commands is required and must contain at least one command")
	}
	commands := make([]string, 0, len(request.Commands))
	for _, command := range request.Commands {
		if trimmed := trimToEmpty(command); trimmed != "" {
			commands = append(commands, trimmed)
		}
	}
	if len(commands) == 0 {
		return empty, nil, domain.Biz("commands must contain at least one non-blank command")
	}
	timeout, err := positiveOrDefault(request.TimeoutSeconds, defaultTimeoutSeconds, "timeoutSeconds")
	if err != nil {
		return empty, nil, err
	}
	ttl, err := nonNegativeOrDefault(request.TTLSecondsAfterFinished, defaultTTLSeconds, "ttlSecondsAfterFinished")
	if err != nil {
		return empty, nil, err
	}
	env, err := sanitizeEnv(request.Env)
	if err != nil {
		return empty, nil, err
	}
	environment, err := s.services.environmentByName(ctx, request.Environment)
	if err != nil {
		return empty, nil, err
	}
	return sandbox.JobSpec{
		Image: image,
		// One script, not one process per command, so a variable set by one line
		// is still set on the next.
		Script:                  strings.Join(commands, "\n"),
		TimeoutSeconds:          timeout,
		TTLSecondsAfterFinished: ttl,
		CPURequest:              request.CPU.requestOr(defaultCPURequest),
		CPULimit:                request.CPU.limitOr(defaultCPULimit),
		MemoryRequest:           request.Memory.requestOr(defaultMemoryRequest),
		MemoryLimit:             request.Memory.limitOr(defaultMemoryLimit),
		Env:                     env,
		CreatedByUserID:         operatorUserID,
	}, environment, nil
}

// ---------------------------------------------------------------------------
// long-lived instances

// CreateInstance starts a long-lived sandbox.
func (s *SandboxService) CreateInstance(ctx context.Context, request InstanceRequest, operatorUserID string) (*domain.SandboxInstance, error) {
	if trimToEmpty(request.Environment) == "" {
		return nil, domain.Biz("environment is required")
	}
	image := trimToEmpty(request.Image)
	if image == "" {
		return nil, domain.Biz("image is required")
	}
	environment, err := s.services.environmentByName(ctx, request.Environment)
	if err != nil {
		return nil, err
	}
	workNamespace := trimToEmpty(domain.Deref(environment.WorkNamespace))
	if workNamespace == "" {
		return nil, domain.Bizf("Environment has no work namespace configured: %s", request.Environment)
	}
	env, err := sanitizeEnv(request.Env)
	if err != nil {
		return nil, err
	}

	sandboxID := domain.NewID()
	name := trimToEmpty(request.Name)
	if name == "" {
		name = sandboxID
	} else {
		// A name is how an operator tells two sandboxes apart, so a duplicate is
		// refused rather than quietly created.
		existing, err := s.services.Sandbox.ListPersistent(ctx, environment.KubernetesApiServer, environment, operatorUserID, "")
		if err != nil {
			return nil, err
		}
		for _, instance := range existing {
			if domain.Deref(instance.Name) == name {
				return nil, domain.Bizf("Sandbox name already exists: %s", name)
			}
		}
	}
	keepalive := request.UseDefaultKeepalive == nil || *request.UseDefaultKeepalive
	spec := sandbox.PersistentSpec{
		SandboxID: sandboxID, Name: name, Image: image,
		CPURequest: trimmedOrNil(request.CPU, true), CPULimit: trimmedOrNil(request.CPU, false),
		MemoryRequest: trimmedOrNil(request.Memory, true), MemoryLimit: trimmedOrNil(request.Memory, false),
		Env: env, CreatedByUserID: operatorUserID, UseDefaultKeepalive: keepalive,
	}
	return s.services.Sandbox.CreatePersistent(ctx, environment.KubernetesApiServer, workNamespace, environment, spec)
}

// trimmedOrNil reads one half of a resource spec, leaving it unset when blank —
// an instance omits the field from the pod spec rather than defaulting it.
func trimmedOrNil(spec *ResourceSpec, wantRequest bool) *string {
	if spec == nil {
		return nil
	}
	value := spec.Limit
	if wantRequest {
		value = spec.Request
	}
	return domain.TrimToNil(value)
}

// ListInstances lists the long-lived sandboxes. A blank environment spans every
// environment that has a work namespace.
func (s *SandboxService) ListInstances(ctx context.Context, environmentName, image string) ([]domain.SandboxInstance, error) {
	var instances []domain.SandboxInstance
	if trimToEmpty(environmentName) != "" {
		environment, err := s.services.environmentByName(ctx, environmentName)
		if err != nil {
			return nil, err
		}
		if trimToEmpty(domain.Deref(environment.WorkNamespace)) == "" {
			return []domain.SandboxInstance{}, nil
		}
		instances, err = s.services.Sandbox.ListPersistent(ctx, environment.KubernetesApiServer, environment, "", image)
		if err != nil {
			return nil, err
		}
	} else {
		environments, err := s.services.Store.Environments().FindAll(ctx)
		if err != nil {
			return nil, err
		}
		for index := range environments {
			environment := &environments[index]
			if trimToEmpty(domain.Deref(environment.WorkNamespace)) == "" {
				continue
			}
			found, err := s.services.Sandbox.ListPersistent(ctx, environment.KubernetesApiServer, environment, "", image)
			if err != nil {
				// One unreachable cluster must not empty the whole listing.
				continue
			}
			instances = append(instances, found...)
		}
	}
	return s.withCreatorNames(ctx, instances)
}

// owned is a sandbox and the environment it was found in.
type owned struct {
	environment *domain.Environment
	instance    *domain.SandboxInstance
}

// findOwned locates a sandbox by id alone. Its endpoints carry no environment —
// an id is unique across the installation — so every environment with a work
// namespace is searched until one answers.
func (s *SandboxService) findOwned(ctx context.Context, sandboxID string) (*owned, error) {
	if trimToEmpty(sandboxID) == "" {
		return nil, domain.Biz("Sandbox id is required")
	}
	environments, err := s.services.Store.Environments().FindAll(ctx)
	if err != nil {
		return nil, err
	}
	for index := range environments {
		environment := &environments[index]
		if trimToEmpty(domain.Deref(environment.WorkNamespace)) == "" {
			continue
		}
		instance, err := s.services.Sandbox.FindPersistent(ctx, environment.KubernetesApiServer, environment, sandboxID)
		if err != nil || instance == nil {
			continue
		}
		return &owned{environment: environment, instance: instance}, nil
	}
	return nil, domain.Bizf("Sandbox not found: %s", sandboxID)
}

// GetInstance loads one long-lived sandbox.
func (s *SandboxService) GetInstance(ctx context.Context, sandboxID string) (*domain.SandboxInstance, error) {
	found, err := s.findOwned(ctx, sandboxID)
	if err != nil {
		return nil, err
	}
	resolved, err := s.withCreatorNames(ctx, []domain.SandboxInstance{*found.instance})
	if err != nil || len(resolved) == 0 {
		return found.instance, err
	}
	return &resolved[0], nil
}

// DeleteInstance tears a long-lived sandbox down.
func (s *SandboxService) DeleteInstance(ctx context.Context, sandboxID string) error {
	found, err := s.findOwned(ctx, sandboxID)
	if err != nil {
		return err
	}
	return s.services.Sandbox.DeletePersistent(ctx, found.environment.KubernetesApiServer,
		domain.Deref(found.environment.WorkNamespace), sandboxID)
}

// ExecInstance runs a command inside a long-lived sandbox.
func (s *SandboxService) ExecInstance(ctx context.Context, sandboxID string, request InstanceExecRequest) (sandbox.ExecResult, error) {
	found, timeout, err := s.prepareExec(ctx, sandboxID, request)
	if err != nil {
		return sandbox.ExecResult{}, err
	}
	return s.services.Sandbox.ExecInstance(ctx, found.environment.KubernetesApiServer,
		domain.Deref(found.environment.WorkNamespace), sandboxID, request.Command, timeout)
}

// StreamExecInstance is ExecInstance reporting output as it arrives.
func (s *SandboxService) StreamExecInstance(ctx context.Context, sandboxID string, request InstanceExecRequest, onLine func(string), onExit func(int)) error {
	found, timeout, err := s.prepareExec(ctx, sandboxID, request)
	if err != nil {
		return err
	}
	return s.services.Sandbox.StreamExecInstance(ctx, found.environment.KubernetesApiServer,
		domain.Deref(found.environment.WorkNamespace), sandboxID, request.Command, timeout, onLine, onExit)
}

func (s *SandboxService) prepareExec(ctx context.Context, sandboxID string, request InstanceExecRequest) (*owned, int, error) {
	if trimToEmpty(request.Command) == "" {
		return nil, 0, domain.Biz("command is required")
	}
	timeout, err := positiveOrDefault(request.TimeoutSeconds, defaultTimeoutSeconds, "timeoutSeconds")
	if err != nil {
		return nil, 0, err
	}
	found, err := s.findOwned(ctx, sandboxID)
	if err != nil {
		return nil, 0, err
	}
	return found, timeout, nil
}

// InstanceTarget resolves a sandbox's pod for the filesystem and terminal
// endpoints, which address it the way they address an application pod.
func (s *SandboxService) InstanceTarget(ctx context.Context, sandboxID string) (environmentName, namespace, pod, container string, err error) {
	found, err := s.findOwned(ctx, sandboxID)
	if err != nil {
		return "", "", "", "", err
	}
	return found.environment.Name, domain.Deref(found.environment.WorkNamespace),
		sandbox.PodName(sandboxID), sandboxContainer, nil
}

// withCreatorNames fills in who created each sandbox: the cluster only knows the
// user id, which is not worth showing on its own.
func (s *SandboxService) withCreatorNames(ctx context.Context, instances []domain.SandboxInstance) ([]domain.SandboxInstance, error) {
	if instances == nil {
		return []domain.SandboxInstance{}, nil
	}
	ids := map[string]bool{}
	for _, instance := range instances {
		if id := domain.Deref(instance.CreatedBy); id != "" {
			ids[id] = true
		}
	}
	names, err := s.services.Users.UsernamesByID(ctx, keysOf(ids))
	if err != nil {
		return instances, err
	}
	for index := range instances {
		if name, ok := names[domain.Deref(instances[index].CreatedBy)]; ok {
			instances[index].CreatedByName = &name
		}
	}
	return instances, nil
}
