package store

import (
	"context"
	"encoding/json"

	"github.com/wellch4n/oops/server/internal/domain"
)

// Codec is the crypto dependency: environment secrets are encrypted at rest.
type Codec interface {
	Encrypt(plaintext string) (string, error)
	Decrypt(ciphertext string) (string, error)
}

func (s *Store) SetCodec(codec Codec) { s.codec = codec }

// GitCredential mirrors Environment.GitCredential; the whole JSON blob is
// encrypted at rest by GitCredentialConverter.
type GitCredential struct {
	Username   *string `json:"username"`
	Password   *string `json:"password"`
	PrivateKey *string `json:"privateKey"`
}

func (credential *GitCredential) isEmpty() bool {
	blank := func(value *string) bool { return value == nil || *value == "" }
	return credential == nil || (blank(credential.Username) && blank(credential.Password) && blank(credential.PrivateKey))
}

// EnvironmentFull is the decrypted environment, mirroring EnvironmentDto.from
// (the Java GET endpoints return the decrypted secrets to the admin UI).
type EnvironmentFull struct {
	ID                  string               `json:"id"`
	Name                string               `json:"name"`
	KubernetesApiServer *KubernetesApiServer `json:"kubernetesApiServer"`
	WorkNamespace       *string              `json:"workNamespace"`
	BuildStorageClass   *string              `json:"buildStorageClass"`
	ImageRepository     *ImageRepository     `json:"imageRepository"`
	GitCredential       *GitCredential       `json:"gitCredential"`
}

func (s *Store) environmentRecordToFull(record *environmentRecord) (*EnvironmentFull, error) {
	environment := &EnvironmentFull{
		ID:                record.ID,
		Name:              record.Name,
		WorkNamespace:     record.WorkNamespace,
		BuildStorageClass: record.BuildStorageClass,
	}
	if record.APIServerURL != nil || record.APIServerToken != nil {
		server := &KubernetesApiServer{URL: record.APIServerURL}
		if record.APIServerToken != nil {
			// An empty stored token decrypts to "" (converter passthrough),
			// which Jackson renders as "" rather than null.
			token, err := s.codec.Decrypt(*record.APIServerToken)
			if err != nil {
				return nil, err
			}
			server.Token = &token
		}
		environment.KubernetesApiServer = server
	}
	if record.ImageRepositoryURL != nil || record.ImageRepositoryUsername != nil || record.ImageRepositoryPassword != nil {
		repository := &ImageRepository{
			URL:      record.ImageRepositoryURL,
			Username: record.ImageRepositoryUsername,
		}
		if record.ImageRepositoryPassword != nil {
			password, err := s.codec.Decrypt(*record.ImageRepositoryPassword)
			if err != nil {
				return nil, err
			}
			repository.Password = &password
		}
		environment.ImageRepository = repository
	}
	if record.GitCredential != nil && *record.GitCredential != "" {
		decrypted, err := s.codec.Decrypt(*record.GitCredential)
		if err != nil {
			return nil, err
		}
		var credential GitCredential
		if err := json.Unmarshal([]byte(decrypted), &credential); err == nil {
			environment.GitCredential = &credential
		}
	}
	return environment, nil
}

func (s *Store) ListEnvironmentsFull(ctx context.Context) ([]EnvironmentFull, error) {
	var records []environmentRecord
	if err := s.orm.WithContext(ctx).Find(&records).Error; err != nil {
		return nil, err
	}
	environments := make([]EnvironmentFull, 0, len(records))
	for i := range records {
		environment, err := s.environmentRecordToFull(&records[i])
		if err != nil {
			return nil, err
		}
		environments = append(environments, *environment)
	}
	return environments, nil
}

func (s *Store) FindEnvironmentByID(ctx context.Context, id string) (*EnvironmentFull, error) {
	var record environmentRecord
	if err := s.orm.WithContext(ctx).Where("id = ?", id).First(&record).Error; err != nil {
		return nil, notFound(err)
	}
	return s.environmentRecordToFull(&record)
}

func (s *Store) FindEnvironmentFullByName(ctx context.Context, name string) (*EnvironmentFull, error) {
	var record environmentRecord
	if err := s.orm.WithContext(ctx).Where("name = ?", name).First(&record).Error; err != nil {
		return nil, notFound(err)
	}
	return s.environmentRecordToFull(&record)
}

func (s *Store) environmentExists(ctx context.Context, name string) bool {
	var count int64
	err := s.orm.WithContext(ctx).Model(&environmentRecord{}).
		Where("name = ?", name).Count(&count).Error
	return err == nil && count > 0
}

func (s *Store) encryptOptional(value *string) (*string, error) {
	if value == nil || *value == "" {
		return value, nil
	}
	encrypted, err := s.codec.Encrypt(*value)
	if err != nil {
		return nil, err
	}
	return &encrypted, nil
}

func (s *Store) encryptGitCredential(credential *GitCredential) (*string, error) {
	if credential.isEmpty() {
		return nil, nil
	}
	encoded, err := json.Marshal(credential)
	if err != nil {
		return nil, err
	}
	encrypted, err := s.codec.Encrypt(string(encoded))
	if err != nil {
		return nil, err
	}
	return &encrypted, nil
}

func (s *Store) CreateEnvironment(ctx context.Context, environment *EnvironmentFull) (string, error) {
	if !domain.IsValidEnvironmentName(environment.Name) {
		return "", domain.Bizf("Invalid environment name: %s", environment.Name)
	}
	if s.environmentExists(ctx, environment.Name) {
		return "", domain.Bizf("Environment already exists: %s", environment.Name)
	}
	record := environmentRecord{ID: domain.NewID(), Name: environment.Name,
		WorkNamespace: environment.WorkNamespace, BuildStorageClass: environment.BuildStorageClass}
	var err error
	if environment.KubernetesApiServer != nil {
		record.APIServerURL = environment.KubernetesApiServer.URL
		if record.APIServerToken, err = s.encryptOptional(environment.KubernetesApiServer.Token); err != nil {
			return "", err
		}
	}
	if environment.ImageRepository != nil {
		record.ImageRepositoryURL = environment.ImageRepository.URL
		record.ImageRepositoryUsername = environment.ImageRepository.Username
		if record.ImageRepositoryPassword, err = s.encryptOptional(environment.ImageRepository.Password); err != nil {
			return "", err
		}
	}
	if record.GitCredential, err = s.encryptGitCredential(environment.GitCredential); err != nil {
		return "", err
	}
	return record.ID, s.orm.WithContext(ctx).Create(&record).Error
}

func (s *Store) requireEnvironmentRow(ctx context.Context, id string) error {
	var count int64
	if err := s.orm.WithContext(ctx).Model(&environmentRecord{}).
		Where("id = ?", id).Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return domain.Bizf("Environment with id %s does not exist.", id)
	}
	return nil
}

func (s *Store) UpdateEnvironmentClusterConfig(ctx context.Context, id string, server *KubernetesApiServer, workNamespace, buildStorageClass *string) error {
	if err := s.requireEnvironmentRow(ctx, id); err != nil {
		return err
	}
	updates := map[string]any{
		"api_server_url": nil, "api_server_token": nil,
		"work_namespace": workNamespace, "build_storage_class": buildStorageClass,
	}
	if server != nil {
		updates["api_server_url"] = server.URL
		token, err := s.encryptOptional(server.Token)
		if err != nil {
			return err
		}
		updates["api_server_token"] = token
	}
	return s.orm.WithContext(ctx).Model(&environmentRecord{}).
		Where("id = ?", id).Updates(updates).Error
}

func (s *Store) UpdateEnvironmentCredentialConfig(ctx context.Context, id string, repository *ImageRepository, gitCredential *GitCredential) error {
	if err := s.requireEnvironmentRow(ctx, id); err != nil {
		return err
	}
	updates := map[string]any{
		"image_repository_url": nil, "image_repository_username": nil, "image_repository_password": nil,
	}
	if repository != nil {
		updates["image_repository_url"] = repository.URL
		updates["image_repository_username"] = repository.Username
		password, err := s.encryptOptional(repository.Password)
		if err != nil {
			return err
		}
		updates["image_repository_password"] = password
	}
	encryptedGitCredential, err := s.encryptGitCredential(gitCredential)
	if err != nil {
		return err
	}
	updates["git_credential"] = encryptedGitCredential
	return s.orm.WithContext(ctx).Model(&environmentRecord{}).
		Where("id = ?", id).Updates(updates).Error
}

func (s *Store) DeleteEnvironment(ctx context.Context, id string) error {
	return s.orm.WithContext(ctx).Where("id = ?", id).Delete(&environmentRecord{}).Error
}
