package store

import (
	"context"
	"errors"

	"github.com/go-sql-driver/mysql"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

var ErrDuplicateName = errors.New("application name already exists")

func isDuplicateKey(err error) bool {
	var mysqlError *mysql.MySQLError
	return errors.As(err, &mysqlError) && mysqlError.Number == 1062
}

func (s *Store) CreateApplication(ctx context.Context, namespace, name, description, icon, ownerID string) (string, error) {
	application := Application{
		ID:          NewNanoID(),
		CreatedTime: Now(),
		Name:        name,
		Namespace:   namespace,
		Owner:       &ownerID,
	}
	if description != "" {
		application.Description = &description
	} else {
		empty := ""
		application.Description = &empty
	}
	if icon != "" {
		application.Icon = &icon
	}
	err := s.orm.WithContext(ctx).Create(&application).Error
	if isDuplicateKey(err) {
		return "", ErrDuplicateName
	}
	return application.ID, err
}

// UpdateApplicationProfile mirrors Application.changeProfile +
// changeCollaborators: description/owner/icon plus the collaborator set
// (de-duplicated, owner excluded).
func (s *Store) UpdateApplicationProfile(ctx context.Context, namespace, name, description, owner, icon string, collaborators []string) error {
	return s.orm.WithContext(ctx).Transaction(func(transaction *gorm.DB) error {
		var application Application
		if err := transaction.Where("namespace = ? AND name = ?", namespace, name).
			First(&application).Error; err != nil {
			return notFound(err)
		}
		updates := map[string]any{"description": description, "owner": nil, "icon": nil}
		if owner != "" {
			updates["owner"] = owner
		}
		if icon != "" {
			updates["icon"] = icon
		}
		if err := transaction.Model(&Application{}).
			Where("namespace = ? AND name = ?", namespace, name).
			Updates(updates).Error; err != nil {
			return err
		}

		if err := transaction.
			Where("namespace = ? AND application_name = ?", namespace, name).
			Delete(&applicationCollaboratorRecord{}).Error; err != nil {
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
			row := applicationCollaboratorRecord{
				ID: NewNanoID(), CreatedTime: Now(),
				Namespace: namespace, ApplicationName: name, UserID: userID,
			}
			if err := transaction.Create(&row).Error; err != nil {
				return err
			}
		}
		return nil
	})
}

// ReplaceEnvironmentBindings rewrites the application_environment rows,
// keeping rows whose environment is still bound (their id and created_time
// carry the binding history).
func (s *Store) ReplaceEnvironmentBindings(ctx context.Context, namespace, applicationName string, environmentNames []string) error {
	return s.orm.WithContext(ctx).Transaction(func(transaction *gorm.DB) error {
		wanted := map[string]struct{}{}
		for _, environmentName := range environmentNames {
			if environmentName != "" {
				wanted[environmentName] = struct{}{}
			}
		}
		var existingRows []EnvironmentBinding
		if err := transaction.
			Where("namespace = ? AND application_name = ?", namespace, applicationName).
			Find(&existingRows).Error; err != nil {
			return err
		}
		existing := map[string]struct{}{}
		for _, row := range existingRows {
			existing[row.EnvironmentName] = struct{}{}
			if _, keep := wanted[row.EnvironmentName]; !keep {
				if err := transaction.
					Where("namespace = ? AND application_name = ? AND environment_name = ?",
						namespace, applicationName, row.EnvironmentName).
					Delete(&EnvironmentBinding{}).Error; err != nil {
					return err
				}
			}
		}
		for environmentName := range wanted {
			if _, present := existing[environmentName]; !present {
				binding := EnvironmentBinding{
					ID: NewNanoID(), CreatedTime: Now(),
					Namespace: namespace, ApplicationName: applicationName,
					EnvironmentName: environmentName,
				}
				if err := transaction.Create(&binding).Error; err != nil {
					return err
				}
			}
		}
		return nil
	})
}

// upsertRecord rewrites a single-row-per-application config table: update the
// existing row's payload columns or insert a fresh identity.
func upsertRecord[T any](ctx context.Context, orm *gorm.DB, namespace, applicationName string,
	record *T, updates map[string]any) error {

	var existing T
	err := orm.WithContext(ctx).Model(record).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&existing).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return orm.WithContext(ctx).Create(record).Error
	}
	if err != nil {
		return err
	}
	return orm.WithContext(ctx).Model(record).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		Updates(updates).Error
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
	sourceConfig := sourceConfigBlob{Type: resolvedType}
	if resolvedType == "GIT" {
		sourceConfig.Repository = repository
	}
	dockerFileField := JSONField[*DockerFileConfig]{}
	if dockerFile != nil {
		dockerFileField = jsonOf(dockerFile)
	}
	record := buildConfigRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		SourceType:         &resolvedType,
		SourceConfig:       jsonOf(sourceConfig),
		DockerFileConfig:   dockerFileField,
		BuildImage:         buildImage,
		EnvironmentConfigs: jsonOf(environmentConfigs),
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"source_type":         record.SourceType,
		"source_config":       record.SourceConfig,
		"docker_file_config":  record.DockerFileConfig,
		"build_image":         record.BuildImage,
		"environment_configs": record.EnvironmentConfigs,
	})
}

func (s *Store) SaveBuildEnvironmentConfigs(ctx context.Context, namespace, applicationName string, configs []BuildEnvironmentConfig) error {
	record := buildConfigRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		EnvironmentConfigs: jsonOf(configs),
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"environment_configs": record.EnvironmentConfigs,
	})
}

func (s *Store) SaveRuntimeSpec(ctx context.Context, namespace, applicationName string,
	environmentConfigs []RuntimeEnvironmentConfig, healthCheck *HealthCheck) error {
	healthCheckField := JSONField[*HealthCheck]{}
	if healthCheck != nil {
		healthCheckField = jsonOf(healthCheck)
	}
	record := runtimeSpecRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		EnvironmentConfigs: jsonOf(environmentConfigs),
		HealthCheck:        healthCheckField,
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"environment_configs": record.EnvironmentConfigs,
		"health_check":        record.HealthCheck,
	})
}

func (s *Store) SaveRuntimeSpecEnvironmentConfigs(ctx context.Context, namespace, applicationName string, configs []RuntimeEnvironmentConfig) error {
	record := runtimeSpecRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		EnvironmentConfigs: jsonOf(configs),
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"environment_configs": record.EnvironmentConfigs,
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
	var existing serviceConfigRecord
	if err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&existing).Error; err == nil && existing.EnvironmentConfigs.Valid {
		for _, row := range existing.EnvironmentConfigs.Data {
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

	record := serviceConfigRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		Port:               port,
		InternalPorts:      jsonOf(internalPorts),
		EnvironmentConfigs: jsonOf(rows),
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"port":                record.Port,
		"internal_ports":      record.InternalPorts,
		"environment_configs": record.EnvironmentConfigs,
	})
}

func (s *Store) SaveExpertConfig(ctx context.Context, namespace, applicationName string, environmentConfigs []ExpertEnvironmentConfig) error {
	record := expertConfigRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Namespace: namespace, ApplicationName: applicationName,
		EnvironmentConfigs: jsonOf(environmentConfigs),
	}
	return upsertRecord(ctx, s.orm, namespace, applicationName, &record, map[string]any{
		"environment_configs": record.EnvironmentConfigs,
	})
}
