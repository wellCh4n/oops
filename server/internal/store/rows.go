package store

import (
	"database/sql"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store/sqltypes"
)

// The row structs are the shape a SELECT scans into, one per table, mirroring
// the migration exactly — a test checks that column for column.
//
// Almost every column is nullable, because the Java backend let Hibernate
// declare them and Hibernate made everything nullable. That is not a statement
// that absent and empty differ for a description or an image tag; it is just
// the shape of the database this version has to keep working against. The
// repositories map NULL to an unset value on the way out, so the domain types
// above them never see a sql.Null*.
//
// Only ids, user.email, user.enabled and the alert-state firing flag are NOT
// NULL, and only because the Java schema said so.

// rowStructs maps each table to the struct a SELECT scans into. Two things read
// it: a test that checks the structs against the migration, and the startup
// check in verify.go that checks them against the live database.
var rowStructs = map[string]any{
	"application":                applicationRow{},
	"application_build_config":   buildConfigRow{},
	"application_environment":    applicationEnvironmentRow{},
	"application_runtime_spec":   runtimeSpecRow{},
	"application_service_config": serviceConfigRow{},
	"application_expert_config":  expertConfigRow{},
	"application_collaborator":   collaboratorRow{},
	"application_alert_state":    alertStateRow{},
	"domain":                     domainRow{},
	"environment":                environmentRow{},
	"external_account":           externalAccountRow{},
	"namespace":                  namespaceRow{},
	"pipeline":                   pipelineRow{},
	"user":                       userRow{},
}

type applicationRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Description sql.NullString       `db:"description"`
	Name        sql.NullString       `db:"name"`
	Namespace   sql.NullString       `db:"namespace"`
	Owner       sql.NullString       `db:"owner"`
	Icon        sql.NullString       `db:"icon"`
}

type buildConfigRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	ApplicationName    sql.NullString       `db:"application_name"`
	BuildImage         sql.NullString       `db:"build_image"`
	EnvironmentConfigs sql.NullString       `db:"environment_configs"`
	Namespace          sql.NullString       `db:"namespace"`
	SourceType         sql.NullString       `db:"source_type"`
	DockerFileConfig   sql.NullString       `db:"docker_file_config"`
	SourceConfig       sql.NullString       `db:"source_config"`
}

type applicationEnvironmentRow struct {
	ID              string               `db:"id"`
	CreatedTime     domain.LocalDateTime `db:"created_time"`
	ApplicationName sql.NullString       `db:"application_name"`
	Environment     sql.NullString       `db:"environment"`
	Namespace       sql.NullString       `db:"namespace"`
}

type runtimeSpecRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	ApplicationName    sql.NullString       `db:"application_name"`
	EnvironmentConfigs sql.NullString       `db:"environment_configs"`
	Namespace          sql.NullString       `db:"namespace"`
	HealthCheck        sql.NullString       `db:"health_check"`
}

type serviceConfigRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	ApplicationName    sql.NullString       `db:"application_name"`
	EnvironmentConfigs sql.NullString       `db:"environment_configs"`
	Namespace          sql.NullString       `db:"namespace"`
	Port               sql.NullInt32        `db:"port"`
	InternalPorts      sql.NullString       `db:"internal_ports"`
}

type expertConfigRow struct {
	ID                 string               `db:"id"`
	CreatedTime        domain.LocalDateTime `db:"created_time"`
	Namespace          sql.NullString       `db:"namespace"`
	ApplicationName    sql.NullString       `db:"application_name"`
	EnvironmentConfigs sql.NullString       `db:"environment_configs"`
}

type collaboratorRow struct {
	ID              string               `db:"id"`
	CreatedTime     domain.LocalDateTime `db:"created_time"`
	Namespace       sql.NullString       `db:"namespace"`
	ApplicationName sql.NullString       `db:"application_name"`
	UserID          sql.NullString       `db:"user_id"`
}

type alertStateRow struct {
	ID               string               `db:"id"`
	CreatedTime      domain.LocalDateTime `db:"created_time"`
	Namespace        sql.NullString       `db:"namespace"`
	ApplicationName  sql.NullString       `db:"application_name"`
	Environment      sql.NullString       `db:"environment"`
	Metric           sql.NullString       `db:"metric"`
	Firing           sqltypes.BitBool     `db:"firing"`
	FiringSince      domain.LocalDateTime `db:"firing_since"`
	LastNotifiedTime domain.LocalDateTime `db:"last_notified_time"`
}

type domainRow struct {
	ID           string               `db:"id"`
	CreatedTime  domain.LocalDateTime `db:"created_time"`
	CertMode     sql.NullString       `db:"cert_mode"`
	CertNotAfter domain.LocalDateTime `db:"cert_not_after"`
	CertPem      Encrypted            `db:"cert_pem"`
	CertSubject  sql.NullString       `db:"cert_subject"`
	Description  sql.NullString       `db:"description"`
	Host         sql.NullString       `db:"host"`
	HTTPS        sql.NullBool         `db:"https"`
	KeyPem       Encrypted            `db:"key_pem"`
	Environment  sql.NullString       `db:"environment"`
}

type environmentRow struct {
	ID                      string                              `db:"id"`
	BuildStorageClass       sql.NullString                      `db:"build_storage_class"`
	ImageRepositoryPassword Encrypted                           `db:"image_repository_password"`
	ImageRepositoryURL      sql.NullString                      `db:"image_repository_url"`
	ImageRepositoryUsername sql.NullString                      `db:"image_repository_username"`
	APIServerToken          Encrypted                           `db:"api_server_token"`
	APIServerURL            sql.NullString                      `db:"api_server_url"`
	Name                    sql.NullString                      `db:"name"`
	WorkNamespace           sql.NullString                      `db:"work_namespace"`
	GitCredential           EncryptedJSON[domain.GitCredential] `db:"git_credential"`
}

type externalAccountRow struct {
	ID             string               `db:"id"`
	CreatedTime    domain.LocalDateTime `db:"created_time"`
	Email          sql.NullString       `db:"email"`
	Provider       sql.NullString       `db:"provider"`
	ProviderUserID sql.NullString       `db:"provider_user_id"`
	UserID         sql.NullString       `db:"user_id"`
}

type namespaceRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Description sql.NullString       `db:"description"`
	Name        sql.NullString       `db:"name"`
}

type pipelineRow struct {
	ID                     string               `db:"id"`
	CreatedTime            domain.LocalDateTime `db:"created_time"`
	Namespace              sql.NullString       `db:"namespace"`
	ApplicationName        sql.NullString       `db:"application_name"`
	Status                 sql.NullString       `db:"status"`
	Artifact               sql.NullString       `db:"artifact"`
	Environment            sql.NullString       `db:"environment"`
	PublishType            sql.NullString       `db:"publish_type"`
	DeployMode             sql.NullString       `db:"deploy_mode"`
	OperatorID             sql.NullString       `db:"operator_id"`
	Message                sql.NullString       `db:"message"`
	TriggerType            sql.NullString       `db:"trigger_type"`
	RollbackFromPipelineID sql.NullString       `db:"rollback_from_pipeline_id"`
	PublishConfig          sql.NullString       `db:"publish_config"`
}

type userRow struct {
	ID          string               `db:"id"`
	CreatedTime domain.LocalDateTime `db:"created_time"`
	Email       string               `db:"email"`
	Password    sql.NullString       `db:"password"`
	Role        sql.NullString       `db:"role"`
	Username    sql.NullString       `db:"username"`
	AccessToken sql.NullString       `db:"access_token"`
	Enabled     bool                 `db:"enabled"`
}
