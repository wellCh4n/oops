package store

import (
	"database/sql"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store/sqltypes"
)

// The row structs are the shape a SELECT scans into, one per table, mirroring
// schema.sql exactly. They are separate from the domain types because the two
// differ where it matters: a row holds a JSON blob as a string and an enum as a
// string, and the mapping functions in each repository turn those into the
// decoded, typed values the rest of the product works in.
//
// Almost every column is NOT NULL (see schema.sql), so the fields are plain Go
// types. The three exceptions carry sql.Null* and are called out where they
// appear.

type applicationRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Description string               `db:"description"`
	Name        string               `db:"name"`
	Namespace   string               `db:"namespace"`
	Owner       string               `db:"owner"`
	Icon        string               `db:"icon"`
}

type buildConfigRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	ApplicationName    string               `db:"application_name"`
	BuildImage         string               `db:"build_image"`
	EnvironmentConfigs string               `db:"environment_configs"`
	Namespace          string               `db:"namespace"`
	SourceType         string               `db:"source_type"`
	DockerFileConfig   string               `db:"docker_file_config"`
	SourceConfig       string               `db:"source_config"`
}

type applicationEnvironmentRow struct {
	ID              string               `db:"id"`
	CreatedTime     domain.LocalDateTime `db:"created_time"`
	ApplicationName string               `db:"application_name"`
	Environment     string               `db:"environment"`
	Namespace       string               `db:"namespace"`
}

type runtimeSpecRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	ApplicationName    string               `db:"application_name"`
	EnvironmentConfigs string               `db:"environment_configs"`
	Namespace          string               `db:"namespace"`
	HealthCheck        string               `db:"health_check"`
}

type serviceConfigRow struct {
	ID              string               `db:"id"`
	CreatedTime     domain.LocalDateTime `db:"created_time"`
	ApplicationName string               `db:"application_name"`
	// EnvironmentConfigs is the host list, including each host's basic-auth hash.
	EnvironmentConfigs string `db:"environment_configs"`
	Namespace          string `db:"namespace"`
	// Port stays nullable: 0 is not a port, but neither is it "unset", and the
	// deploy path branches on which of the two it is.
	Port          sql.NullInt32 `db:"port"`
	InternalPorts string        `db:"internal_ports"`
}

type expertConfigRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	Namespace          string               `db:"namespace"`
	ApplicationName    string               `db:"application_name"`
	EnvironmentConfigs string               `db:"environment_configs"`
}

type collaboratorRow struct {
	ID              string               `db:"id"`
	CreatedTime     domain.LocalDateTime `db:"created_time"`
	Namespace       string               `db:"namespace"`
	ApplicationName string               `db:"application_name"`
	UserID          string               `db:"user_id"`
}

type alertStateRow struct {
	ID               string               `db:"id"`
	CreatedTime      domain.LocalDateTime `db:"created_time"`
	Namespace        string               `db:"namespace"`
	ApplicationName  string               `db:"application_name"`
	Environment      string               `db:"environment"`
	Metric           string               `db:"metric"`
	Firing           sqltypes.BitBool     `db:"firing"`
	FiringSince      domain.LocalDateTime `db:"firing_since"`
	LastNotifiedTime domain.LocalDateTime `db:"last_notified_time"`
}

type domainRow struct {
	ID           string               `db:"id"`
	CreatedTime  domain.LocalDateTime `db:"created_time"`
	CertMode     string               `db:"cert_mode"`
	CertNotAfter domain.LocalDateTime `db:"cert_not_after"`
	CertPem      string               `db:"cert_pem"`
	CertSubject  string               `db:"cert_subject"`
	Description  string               `db:"description"`
	Host         string               `db:"host"`
	HTTPS        bool                 `db:"https"`
	KeyPem       string               `db:"key_pem"`
	Environment  string               `db:"environment"`
}

type environmentRow struct {
	ID                      string `db:"id"`
	BuildStorageClass       string `db:"build_storage_class"`
	ImageRepositoryPassword string `db:"image_repository_password"`
	ImageRepositoryURL      string `db:"image_repository_url"`
	ImageRepositoryUsername string `db:"image_repository_username"`
	APIServerToken          string `db:"api_server_token"`
	APIServerURL            string `db:"api_server_url"`
	Name                    string `db:"name"`
	WorkNamespace           string `db:"work_namespace"`
	GitCredential           string `db:"git_credential"`
}

type externalAccountRow struct {
	ID             string               `db:"id"`
	CreatedTime    domain.LocalDateTime `db:"created_time"`
	Email          string               `db:"email"`
	Provider       string               `db:"provider"`
	ProviderUserID string               `db:"provider_user_id"`
	UserID         string               `db:"user_id"`
}

type namespaceRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Description string               `db:"description"`
	Name        string               `db:"name"`
}

type pipelineRow struct {
	ID                     string               `db:"id"`
	CreatedTime            domain.LocalDateTime `db:"created_time"`
	Namespace              string               `db:"namespace"`
	ApplicationName        string               `db:"application_name"`
	Status                 string               `db:"status"`
	Artifact               string               `db:"artifact"`
	Environment            string               `db:"environment"`
	PublishType            string               `db:"publish_type"`
	DeployMode             string               `db:"deploy_mode"`
	OperatorID             string               `db:"operator_id"`
	Message                string               `db:"message"`
	TriggerType            string               `db:"trigger_type"`
	RollbackFromPipelineID string               `db:"rollback_from_pipeline_id"`
	PublishConfig          string               `db:"publish_config"`
}

type userRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Email       string               `db:"email"`
	Password    string               `db:"password"`
	Role        string               `db:"role"`
	Username    string               `db:"username"`
	// AccessToken stays nullable: its unique key admits many NULLs but only one
	// empty string, so '' would let just one account exist without a token.
	AccessToken sql.NullString `db:"access_token"`
	Enabled     bool           `db:"enabled"`
}
