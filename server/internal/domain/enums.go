package domain

type ApplicationSourceType string

const (
	SourceGit ApplicationSourceType = "GIT"
	SourceZip ApplicationSourceType = "ZIP"
)

type DeployMode string

const (
	DeployImmediate DeployMode = "IMMEDIATE"
	DeployManual    DeployMode = "MANUAL"
)

type DockerFileType string

const (
	DockerFileBuiltin DockerFileType = "BUILTIN"
	DockerFileUser    DockerFileType = "USER"
)

type DomainCertMode string

const (
	CertModeAuto     DomainCertMode = "AUTO"
	CertModeUploaded DomainCertMode = "UPLOADED"
)

type ExternalAccountProvider string

const ProviderFeishu ExternalAccountProvider = "FEISHU"

type PipelineStatus string

const (
	PipelineInitialized    PipelineStatus = "INITIALIZED"
	PipelineRunning        PipelineStatus = "RUNNING"
	PipelineBuildSucceeded PipelineStatus = "BUILD_SUCCEEDED"
	PipelineDeploying      PipelineStatus = "DEPLOYING"
	PipelineRollingOut     PipelineStatus = "ROLLING_OUT"
	PipelineStopped        PipelineStatus = "STOPPED"
	PipelineSucceeded      PipelineStatus = "SUCCEEDED"
	PipelineError          PipelineStatus = "ERROR"
)

type PipelineTriggerType string

const (
	TriggerRelease  PipelineTriggerType = "RELEASE"
	TriggerRollback PipelineTriggerType = "ROLLBACK"
)

type UserRole string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type SandboxInstanceStatus string

const (
	SandboxPending     SandboxInstanceStatus = "PENDING"
	SandboxRunning     SandboxInstanceStatus = "RUNNING"
	SandboxFailed      SandboxInstanceStatus = "FAILED"
	SandboxTerminating SandboxInstanceStatus = "TERMINATING"
)

type ApplicationPriority string

const (
	PriorityHigh   ApplicationPriority = "HIGH"
	PriorityNormal ApplicationPriority = "NORMAL"
	PriorityLow    ApplicationPriority = "LOW"
)

// Labels shared by every Kubernetes resource OOPS manages.
const (
	LabelType    = "oops.type"
	LabelAppName = "oops.app.name"

	TypeApplication = "APPLICATION"
	TypePipeline    = "PIPELINE"
	TypeIDE         = "IDE"
	TypeSandbox     = "sandbox"
)
