package httpapi

import (
	"context"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// Sandbox defaults mirror SandboxDefaults.
const (
	sandboxDefaultTimeoutSeconds = 300
	sandboxDefaultTTLSeconds     = 60
	sandboxDefaultCPURequest     = "100m"
	sandboxDefaultCPULimit       = "1"
	sandboxDefaultMemoryRequest  = "128Mi"
	sandboxDefaultMemoryLimit    = "512Mi"
)

var envVariableNamePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func sanitizeSandboxEnv(env map[string]string) map[string]string {
	sanitized := map[string]string{}
	for name, value := range env {
		if envVariableNamePattern.MatchString(name) {
			sanitized[name] = value
		}
	}
	return sanitized
}

type sandboxResourceSpec struct {
	Request string `json:"request"`
	Limit   string `json:"limit"`
}

func (s *Server) listSandboxImages(c *gin.Context) {
	images := append([]string{"alpine-mate"}, s.cfg.Oops.Sandbox.Images...)
	sort.Strings(images)
	c.JSON(http.StatusOK, ok(images))
}

func firstNonBlank(requested, fallback string) string {
	if strings.TrimSpace(requested) != "" {
		return strings.TrimSpace(requested)
	}
	return fallback
}

// sandboxEnvironment resolves an environment with a work namespace.
func (s *Server) sandboxEnvironment(ctx context.Context, environmentName string) (*k8s.Cluster, *store.EnvironmentFull, string, error) {
	environment, err := s.store.FindEnvironmentFullByName(ctx, environmentName)
	if err != nil {
		return nil, nil, "", fmt.Errorf("Environment not found: %s", environmentName)
	}
	workNamespace := ""
	if environment.WorkNamespace != nil {
		workNamespace = strings.TrimSpace(*environment.WorkNamespace)
	}
	if workNamespace == "" {
		return nil, nil, "", fmt.Errorf("Environment has no work namespace configured: %s", environmentName)
	}
	cluster, err := s.clusterForEnvironment(environment)
	return cluster, environment, workNamespace, err
}

type sandboxExecutionRequest struct {
	Environment             string               `json:"environment"`
	Image                   string               `json:"image"`
	Commands                []string             `json:"commands"`
	TimeoutSeconds          *int                 `json:"timeoutSeconds"`
	TTLSecondsAfterFinished *int                 `json:"ttlSecondsAfterFinished"`
	CPU                     *sandboxResourceSpec `json:"cpu"`
	Memory                  *sandboxResourceSpec `json:"memory"`
	Env                     map[string]string    `json:"env"`
	Stream                  *bool                `json:"stream"`
}

func (s *Server) sandboxExecute(c *gin.Context) {
	var request sandboxExecutionRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Request body is required"))
		return
	}
	if strings.TrimSpace(request.Environment) == "" {
		c.JSON(http.StatusOK, fail("environment is required"))
		return
	}
	if strings.TrimSpace(request.Image) == "" {
		c.JSON(http.StatusOK, fail("image is required"))
		return
	}
	commands := []string{}
	for _, command := range request.Commands {
		if strings.TrimSpace(command) != "" {
			commands = append(commands, strings.TrimSpace(command))
		}
	}
	if len(commands) == 0 {
		c.JSON(http.StatusOK, fail("commands is required and must contain at least one command"))
		return
	}
	cluster, _, workNamespace, err := s.sandboxEnvironment(c.Request.Context(), strings.TrimSpace(request.Environment))
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}

	timeoutSeconds := sandboxDefaultTimeoutSeconds
	if request.TimeoutSeconds != nil && *request.TimeoutSeconds > 0 {
		timeoutSeconds = *request.TimeoutSeconds
	}
	ttl := sandboxDefaultTTLSeconds
	if request.TTLSecondsAfterFinished != nil && *request.TTLSecondsAfterFinished >= 0 {
		ttl = *request.TTLSecondsAfterFinished
	}
	cpuRequest, cpuLimit := sandboxDefaultCPURequest, sandboxDefaultCPULimit
	memoryRequest, memoryLimit := sandboxDefaultMemoryRequest, sandboxDefaultMemoryLimit
	if request.CPU != nil {
		cpuRequest = firstNonBlank(request.CPU.Request, cpuRequest)
		cpuLimit = firstNonBlank(request.CPU.Limit, cpuLimit)
	}
	if request.Memory != nil {
		memoryRequest = firstNonBlank(request.Memory.Request, memoryRequest)
		memoryLimit = firstNonBlank(request.Memory.Limit, memoryLimit)
	}
	spec := &k8s.SandboxJobSpec{
		Image:                   strings.TrimSpace(request.Image),
		Command:                 strings.Join(commands, "\n"),
		TimeoutSeconds:          timeoutSeconds,
		TTLSecondsAfterFinished: ttl,
		CPURequest:              cpuRequest,
		CPULimit:                cpuLimit,
		MemoryRequest:           memoryRequest,
		MemoryLimit:             memoryLimit,
		Env:                     sanitizeSandboxEnv(request.Env),
		CreatedByUserID:         principalFrom(c).UserID,
	}
	sandboxID := domain.NewID()

	if request.Stream != nil && *request.Stream {
		s.streamSandboxRun(c, func(emit func(string) error) (int, error) {
			return k8s.RunSandboxJob(c.Request.Context(), cluster, workNamespace, sandboxID, spec, emit)
		})
		return
	}
	var output strings.Builder
	exitCode, err := k8s.RunSandboxJob(c.Request.Context(), cluster, workNamespace, sandboxID, spec, func(line string) error {
		output.WriteString(line)
		output.WriteString("\n")
		return nil
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail("Sandbox execution failed: "+err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{"exitCode": exitCode, "output": output.String()}))
}

// streamSandboxRun renders SSE "log" events per line and a final "exit" event,
// mirroring the SseEmitter protocol.
func (s *Server) streamSandboxRun(c *gin.Context, run func(emit func(string) error) (int, error)) {
	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("X-Accel-Buffering", "no")
	c.Writer.Flush()
	emit := func(line string) error {
		if _, err := fmt.Fprintf(c.Writer, "event:log\ndata:%s\n\n", line); err != nil {
			return err
		}
		c.Writer.Flush()
		return nil
	}
	exitCode, err := run(emit)
	if err != nil {
		return
	}
	fmt.Fprintf(c.Writer, "event:exit\ndata:%d\n\n", exitCode)
	c.Writer.Flush()
}

type sandboxInstanceCreateRequest struct {
	Environment         string               `json:"environment"`
	Image               string               `json:"image"`
	Name                string               `json:"name"`
	CPU                 *sandboxResourceSpec `json:"cpu"`
	Memory              *sandboxResourceSpec `json:"memory"`
	Env                 map[string]string    `json:"env"`
	UseDefaultKeepalive *bool                `json:"useDefaultKeepalive"`
}

func (s *Server) createSandboxInstance(c *gin.Context) {
	var request sandboxInstanceCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Request body is required"))
		return
	}
	environmentName := strings.TrimSpace(request.Environment)
	image := strings.TrimSpace(request.Image)
	name := strings.TrimSpace(request.Name)
	if environmentName == "" {
		c.JSON(http.StatusOK, fail("environment is required"))
		return
	}
	if image == "" {
		c.JSON(http.StatusOK, fail("image is required"))
		return
	}
	cluster, _, workNamespace, err := s.sandboxEnvironment(c.Request.Context(), environmentName)
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	callerID := principalFrom(c).UserID
	sandboxID := domain.NewID()
	resolvedName := name
	if resolvedName == "" {
		resolvedName = sandboxID
	} else {
		existing, err := k8s.ListPersistentSandboxes(c.Request.Context(), cluster, workNamespace, environmentName, callerID, "")
		if err == nil {
			for _, instance := range existing {
				if instance.Name == resolvedName {
					c.JSON(http.StatusOK, fail("Sandbox name already exists: "+resolvedName))
					return
				}
			}
		}
	}
	spec := &k8s.PersistentSandboxSpec{
		SandboxID:           sandboxID,
		Name:                resolvedName,
		Image:               image,
		Env:                 sanitizeSandboxEnv(request.Env),
		CreatedByUserID:     callerID,
		UseDefaultKeepalive: request.UseDefaultKeepalive == nil || *request.UseDefaultKeepalive,
	}
	if request.CPU != nil {
		spec.CPURequest = strings.TrimSpace(request.CPU.Request)
		spec.CPULimit = strings.TrimSpace(request.CPU.Limit)
	}
	if request.Memory != nil {
		spec.MemoryRequest = strings.TrimSpace(request.Memory.Request)
		spec.MemoryLimit = strings.TrimSpace(request.Memory.Limit)
	}
	instance, err := k8s.CreatePersistentSandbox(c.Request.Context(), cluster, workNamespace, environmentName, spec)
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	s.fillSandboxCreatorNames(c, []k8s.SandboxInstanceView{*instance})
	c.JSON(http.StatusOK, ok(instance))
}

func (s *Server) fillSandboxCreatorNames(c *gin.Context, instances []k8s.SandboxInstanceView) {
	ids := []string{}
	for _, instance := range instances {
		if instance.CreatedBy != "" {
			ids = append(ids, instance.CreatedBy)
		}
	}
	names, err := s.store.UsernamesByIDs(c.Request.Context(), ids)
	if err != nil {
		return
	}
	for i := range instances {
		if name, found := names[instances[i].CreatedBy]; found {
			instances[i].CreatedByName = &name
		}
	}
}

func (s *Server) listSandboxInstances(c *gin.Context) {
	ctx := c.Request.Context()
	environmentName := strings.TrimSpace(c.Query("environment"))
	image := strings.TrimSpace(c.Query("image"))
	instances := []k8s.SandboxInstanceView{}
	appendFor := func(environment store.EnvironmentFull) {
		if environment.WorkNamespace == nil || strings.TrimSpace(*environment.WorkNamespace) == "" {
			return
		}
		cluster, err := s.clusterForEnvironment(&environment)
		if err != nil {
			return
		}
		items, err := k8s.ListPersistentSandboxes(ctx, cluster, *environment.WorkNamespace, environment.Name, "", image)
		if err == nil {
			instances = append(instances, items...)
		}
	}
	if environmentName != "" {
		environment, err := s.store.FindEnvironmentFullByName(ctx, environmentName)
		if err != nil {
			c.JSON(http.StatusOK, fail("Environment not found: "+environmentName))
			return
		}
		appendFor(*environment)
	} else {
		environments, err := s.store.ListEnvironmentsFull(ctx)
		if err != nil {
			c.JSON(http.StatusInternalServerError, fail(err.Error()))
			return
		}
		for _, environment := range environments {
			appendFor(environment)
		}
	}
	s.fillSandboxCreatorNames(c, instances)
	c.JSON(http.StatusOK, ok(instances))
}

// findOwnedSandbox mirrors findOwned: search every environment's work namespace.
func (s *Server) findOwnedSandbox(c *gin.Context, sandboxID string) (*k8s.Cluster, *store.EnvironmentFull, string, *k8s.SandboxInstanceView, bool) {
	ctx := c.Request.Context()
	if strings.TrimSpace(sandboxID) == "" {
		c.JSON(http.StatusOK, fail("Sandbox id is required"))
		return nil, nil, "", nil, false
	}
	environments, err := s.store.ListEnvironmentsFull(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return nil, nil, "", nil, false
	}
	for i := range environments {
		environment := &environments[i]
		if environment.WorkNamespace == nil || strings.TrimSpace(*environment.WorkNamespace) == "" {
			continue
		}
		cluster, err := s.clusterForEnvironment(environment)
		if err != nil {
			continue
		}
		instance, err := k8s.FindPersistentSandbox(ctx, cluster, *environment.WorkNamespace, environment.Name, sandboxID)
		if err != nil || instance == nil {
			continue
		}
		return cluster, environment, *environment.WorkNamespace, instance, true
	}
	c.JSON(http.StatusOK, fail("Sandbox not found: "+sandboxID))
	return nil, nil, "", nil, false
}

func (s *Server) getSandboxInstance(c *gin.Context) {
	_, _, _, instance, found := s.findOwnedSandbox(c, c.Param("id"))
	if !found {
		return
	}
	s.fillSandboxCreatorNames(c, []k8s.SandboxInstanceView{*instance})
	c.JSON(http.StatusOK, ok(instance))
}

func (s *Server) deleteSandboxInstance(c *gin.Context) {
	cluster, _, workNamespace, _, found := s.findOwnedSandbox(c, c.Param("id"))
	if !found {
		return
	}
	if err := k8s.DeletePersistentSandbox(c.Request.Context(), cluster, workNamespace, c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) execSandboxInstance(c *gin.Context) {
	var request struct {
		Command        string `json:"command"`
		TimeoutSeconds *int   `json:"timeoutSeconds"`
		Stream         *bool  `json:"stream"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || strings.TrimSpace(request.Command) == "" {
		c.JSON(http.StatusOK, fail("command is required"))
		return
	}
	cluster, _, workNamespace, instance, found := s.findOwnedSandbox(c, c.Param("id"))
	if !found {
		return
	}
	if instance.Status != "RUNNING" {
		c.JSON(http.StatusOK, fail("Sandbox is not running: "+instance.Status))
		return
	}
	timeoutSeconds := sandboxDefaultTimeoutSeconds
	if request.TimeoutSeconds != nil && *request.TimeoutSeconds > 0 {
		timeoutSeconds = *request.TimeoutSeconds
	}
	command := strings.TrimSpace(request.Command)

	if request.Stream != nil && *request.Stream {
		s.streamSandboxRun(c, func(emit func(string) error) (int, error) {
			return k8s.ExecSandboxInstance(c.Request.Context(), cluster, workNamespace, c.Param("id"), command, timeoutSeconds, emit)
		})
		return
	}
	var output strings.Builder
	exitCode, err := k8s.ExecSandboxInstance(c.Request.Context(), cluster, workNamespace, c.Param("id"), command, timeoutSeconds, func(line string) error {
		output.WriteString(line)
		output.WriteString("\n")
		return nil
	})
	if err != nil {
		c.JSON(http.StatusOK, fail("Sandbox exec failed: "+err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{"exitCode": exitCode, "output": output.String()}))
}

// sandboxFilesTarget resolves the running instance's pod for the file APIs.
func (s *Server) sandboxFilesTarget(c *gin.Context) (*k8s.Cluster, string, string, bool) {
	cluster, _, workNamespace, instance, found := s.findOwnedSandbox(c, c.Param("id"))
	if !found {
		return nil, "", "", false
	}
	if instance.Status != "RUNNING" {
		c.JSON(http.StatusOK, fail("Sandbox is not running: "+instance.Status))
		return nil, "", "", false
	}
	return cluster, workNamespace, "oops-sandbox-" + c.Param("id") + "-0", true
}

// sandboxTerminalWebSocket reuses the pod terminal against the sandbox pod.
func (s *Server) sandboxTerminalWebSocket(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	connection, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	sink := &wsSink{connection: connection}
	ctx, cancel := context.WithCancel(c.Request.Context())
	defer cancel()
	s.runTerminalExec(ctx, cancel, cluster, workNamespace, podName, "sandbox", connection, sink)
}
