package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/go-sql-driver/mysql"
	"golang.org/x/crypto/bcrypt"
)

var ErrDuplicateName = errors.New("application name already exists")

func isDuplicateKey(err error) bool {
	var mysqlError *mysql.MySQLError
	return errors.As(err, &mysqlError) && mysqlError.Number == 1062
}

func (s *Store) CreateApplication(ctx context.Context, namespace, name, description, icon, ownerID string) (string, error) {
	id := NewNanoID()
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO application (id, created_time, name, description, icon, namespace, owner)
		 VALUES (?, ?, ?, ?, NULLIF(?, ''), ?, ?)`,
		id, Now(), name, description, icon, namespace, ownerID)
	if isDuplicateKey(err) {
		return "", ErrDuplicateName
	}
	return id, err
}

// UpdateApplicationProfile mirrors Application.changeProfile +
// changeCollaborators: description/owner/icon plus the collaborator set
// (de-duplicated, owner excluded).
func (s *Store) UpdateApplicationProfile(ctx context.Context, namespace, name, description, owner, icon string, collaborators []string) error {
	transaction, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer transaction.Rollback()

	result, err := transaction.ExecContext(ctx,
		`UPDATE application SET description = ?, owner = NULLIF(?, ''), icon = NULLIF(?, '')
		 WHERE namespace = ? AND name = ?`,
		description, owner, icon, namespace, name)
	if err != nil {
		return err
	}
	if rows, _ := result.RowsAffected(); rows == 0 {
		// The row may exist with identical values; ensure it exists at all.
		var exists int
		if err := transaction.QueryRowContext(ctx,
			"SELECT COUNT(*) FROM application WHERE namespace = ? AND name = ?",
			namespace, name).Scan(&exists); err != nil {
			return err
		}
		if exists == 0 {
			return ErrNotFound
		}
	}

	if _, err := transaction.ExecContext(ctx,
		"DELETE FROM application_collaborator WHERE namespace = ? AND application_name = ?",
		namespace, name); err != nil {
		return err
	}
	seen := map[string]struct{}{}
	for _, userID := range collaborators {
		if userID == "" || userID == owner {
			continue
		}
		if _, duplicate := seen[userID]; duplicate {
			continue
		}
		seen[userID] = struct{}{}
		if _, err := transaction.ExecContext(ctx,
			`INSERT INTO application_collaborator (id, created_time, namespace, application_name, user_id)
			 VALUES (?, ?, ?, ?, ?)`,
			NewNanoID(), Now(), namespace, name, userID); err != nil {
			return err
		}
	}
	return transaction.Commit()
}

// ReplaceEnvironmentBindings rewrites the application_environment rows,
// keeping rows whose environment is still bound (their id and created_time
// carry the binding history).
func (s *Store) ReplaceEnvironmentBindings(ctx context.Context, namespace, applicationName string, environmentNames []string) error {
	transaction, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer transaction.Rollback()

	wanted := map[string]struct{}{}
	for _, environmentName := range environmentNames {
		if environmentName != "" {
			wanted[environmentName] = struct{}{}
		}
	}

	rows, err := transaction.QueryContext(ctx,
		"SELECT environment_name FROM application_environment WHERE namespace = ? AND application_name = ?",
		namespace, applicationName)
	if err != nil {
		return err
	}
	existing := map[string]struct{}{}
	for rows.Next() {
		var environmentName string
		if err := rows.Scan(&environmentName); err != nil {
			rows.Close()
			return err
		}
		existing[environmentName] = struct{}{}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	for environmentName := range existing {
		if _, keep := wanted[environmentName]; !keep {
			if _, err := transaction.ExecContext(ctx,
				`DELETE FROM application_environment
				 WHERE namespace = ? AND application_name = ? AND environment_name = ?`,
				namespace, applicationName, environmentName); err != nil {
				return err
			}
		}
	}
	for environmentName := range wanted {
		if _, present := existing[environmentName]; !present {
			if _, err := transaction.ExecContext(ctx,
				`INSERT INTO application_environment (id, created_time, namespace, application_name, environment_name)
				 VALUES (?, ?, ?, ?, ?)`,
				NewNanoID(), Now(), namespace, applicationName, environmentName); err != nil {
				return err
			}
		}
	}
	return transaction.Commit()
}

func encodeJSON(value any) (any, error) {
	if value == nil {
		return nil, nil
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	return string(encoded), nil
}

// upsert rewrites a single-row-per-application config table.
func (s *Store) upsert(ctx context.Context, table, namespace, applicationName string, columns map[string]any) error {
	var id string
	err := s.db.QueryRowContext(ctx,
		fmt.Sprintf("SELECT id FROM %s WHERE namespace = ? AND application_name = ?", table),
		namespace, applicationName).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		names := []string{"id", "created_time", "namespace", "application_name"}
		values := []any{NewNanoID(), Now(), namespace, applicationName}
		for column, value := range columns {
			names = append(names, column)
			values = append(values, value)
		}
		placeholders := strings.TrimSuffix(strings.Repeat("?,", len(names)), ",")
		_, err = s.db.ExecContext(ctx,
			fmt.Sprintf("INSERT INTO %s (%s) VALUES (%s)", table, strings.Join(names, ", "), placeholders),
			values...)
		return err
	}
	if err != nil {
		return err
	}
	assignments := []string{}
	values := []any{}
	for column, value := range columns {
		assignments = append(assignments, column+" = ?")
		values = append(values, value)
	}
	values = append(values, id)
	_, err = s.db.ExecContext(ctx,
		fmt.Sprintf("UPDATE %s SET %s WHERE id = ?", table, strings.Join(assignments, ", ")),
		values...)
	return err
}

// SaveBuildConfig mirrors updateApplicationBuildConfig: a full rewrite, with
// source_config rebuilt from sourceType + repository like BuildConfig.toDomain.
func (s *Store) SaveBuildConfig(ctx context.Context, namespace, applicationName string,
	sourceType, repository *string, dockerFile *DockerFileConfig, buildImage *string,
	environmentConfigs []BuildEnvironmentConfig) error {

	resolvedType := "GIT"
	if sourceType != nil && *sourceType != "" {
		resolvedType = *sourceType
	}
	sourceConfig := map[string]any{"type": resolvedType}
	if resolvedType == "GIT" {
		sourceConfig["repository"] = repository
	}
	sourceConfigJSON, err := encodeJSON(sourceConfig)
	if err != nil {
		return err
	}
	dockerFileJSON, err := encodeJSON(dockerFile)
	if err != nil {
		return err
	}
	environmentConfigsJSON, err := encodeJSON(environmentConfigs)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_build_config", namespace, applicationName, map[string]any{
		"source_type":         resolvedType,
		"source_config":       sourceConfigJSON,
		"docker_file_config":  dockerFileJSON,
		"build_image":         buildImage,
		"environment_configs": environmentConfigsJSON,
	})
}

func (s *Store) SaveBuildEnvironmentConfigs(ctx context.Context, namespace, applicationName string, configs []BuildEnvironmentConfig) error {
	encoded, err := encodeJSON(configs)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_build_config", namespace, applicationName, map[string]any{
		"environment_configs": encoded,
	})
}

func (s *Store) SaveRuntimeSpec(ctx context.Context, namespace, applicationName string,
	environmentConfigs []RuntimeEnvironmentConfig, healthCheck *HealthCheck) error {
	environmentConfigsJSON, err := encodeJSON(environmentConfigs)
	if err != nil {
		return err
	}
	healthCheckJSON, err := encodeJSON(healthCheck)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_runtime_spec", namespace, applicationName, map[string]any{
		"environment_configs": environmentConfigsJSON,
		"health_check":        healthCheckJSON,
	})
}

func (s *Store) SaveRuntimeSpecEnvironmentConfigs(ctx context.Context, namespace, applicationName string, configs []RuntimeEnvironmentConfig) error {
	encoded, err := encodeJSON(configs)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_runtime_spec", namespace, applicationName, map[string]any{
		"environment_configs": encoded,
	})
}

// ServiceEnvironmentConfigInput carries the write-only plaintext password.
type ServiceEnvironmentConfigInput struct {
	EnvironmentName   *string `json:"environmentName"`
	Host              *string `json:"host"`
	HTTPS             *bool   `json:"https"`
	BasicAuthEnabled  *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername *string `json:"basicAuthUsername"`
	BasicAuthPassword *string `json:"basicAuthPassword"`
}

// SaveServiceConfig mirrors updateApplicationServiceConfig: the whole config
// is rewritten; a non-blank basicAuthPassword is BCrypt-hashed, a blank one
// carries the stored hash forward for the same environment+host.
func (s *Store) SaveServiceConfig(ctx context.Context, namespace, applicationName string,
	port *int, internalPorts []int, environmentConfigs []ServiceEnvironmentConfigInput) error {

	storedHashes := map[string]string{}
	if existing, err := s.findServiceEnvironmentRows(ctx, namespace, applicationName); err == nil {
		for _, row := range existing {
			if row.EnvironmentName != nil && row.Host != nil && row.BasicAuthPasswordHash != nil {
				storedHashes[*row.EnvironmentName+"|"+*row.Host] = *row.BasicAuthPasswordHash
			}
		}
	}

	rows := make([]serviceEnvironmentConfigRow, 0, len(environmentConfigs))
	for _, input := range environmentConfigs {
		row := serviceEnvironmentConfigRow{
			EnvironmentName:   input.EnvironmentName,
			Host:              input.Host,
			HTTPS:             input.HTTPS,
			BasicAuthEnabled:  input.BasicAuthEnabled,
			BasicAuthUsername: input.BasicAuthUsername,
		}
		if input.BasicAuthPassword != nil && *input.BasicAuthPassword != "" {
			hash, err := bcrypt.GenerateFromPassword([]byte(*input.BasicAuthPassword), bcrypt.DefaultCost)
			if err != nil {
				return err
			}
			hashed := string(hash)
			row.BasicAuthPasswordHash = &hashed
		} else if input.EnvironmentName != nil && input.Host != nil {
			if stored, found := storedHashes[*input.EnvironmentName+"|"+*input.Host]; found {
				row.BasicAuthPasswordHash = &stored
			}
		}
		rows = append(rows, row)
	}

	internalPortsJSON, err := encodeJSON(internalPorts)
	if err != nil {
		return err
	}
	environmentConfigsJSON, err := encodeJSON(rows)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_service_config", namespace, applicationName, map[string]any{
		"port":                port,
		"internal_ports":      internalPortsJSON,
		"environment_configs": environmentConfigsJSON,
	})
}

func (s *Store) findServiceEnvironmentRows(ctx context.Context, namespace, applicationName string) ([]serviceEnvironmentConfigRow, error) {
	var blob sql.NullString
	err := s.db.QueryRowContext(ctx,
		"SELECT environment_configs FROM application_service_config WHERE namespace = ? AND application_name = ?",
		namespace, applicationName).Scan(&blob)
	if err != nil {
		return nil, err
	}
	var rows []serviceEnvironmentConfigRow
	if err := decodeJSONColumn(blob, &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *Store) SaveExpertConfig(ctx context.Context, namespace, applicationName string, environmentConfigs []ExpertEnvironmentConfig) error {
	encoded, err := encodeJSON(environmentConfigs)
	if err != nil {
		return err
	}
	return s.upsert(ctx, "application_expert_config", namespace, applicationName, map[string]any{
		"environment_configs": encoded,
	})
}
