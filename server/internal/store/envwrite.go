package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"regexp"
)

// Codec is the crypto dependency: environment secrets are encrypted at rest.
type Codec interface {
	Encrypt(plaintext string) (string, error)
	Decrypt(ciphertext string) (string, error)
}

func (s *Store) SetCodec(codec Codec) { s.codec = codec }

var environmentNamePattern = regexp.MustCompile(`^[A-Za-z]([-A-Za-z0-9]*[A-Za-z0-9])?$`)

func IsValidEnvironmentName(name string) bool {
	return name != "" && len(name) <= 24 && environmentNamePattern.MatchString(name)
}

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

const environmentColumns = `id, name, api_server_url, api_server_token, work_namespace,
	build_storage_class, image_repository_url, image_repository_username,
	image_repository_password, git_credential`

func (s *Store) scanEnvironment(scanner interface{ Scan(...any) error }) (*EnvironmentFull, error) {
	var environment EnvironmentFull
	var apiServerURL, apiServerToken, repositoryURL, repositoryUsername, repositoryPassword, gitCredential sql.NullString
	err := scanner.Scan(&environment.ID, &environment.Name, &apiServerURL, &apiServerToken,
		&environment.WorkNamespace, &environment.BuildStorageClass,
		&repositoryURL, &repositoryUsername, &repositoryPassword, &gitCredential)
	if err != nil {
		return nil, err
	}
	if apiServerURL.Valid || apiServerToken.Valid {
		server := &KubernetesApiServer{}
		if apiServerURL.Valid {
			server.URL = &apiServerURL.String
		}
		if apiServerToken.Valid && apiServerToken.String != "" {
			token, err := s.codec.Decrypt(apiServerToken.String)
			if err != nil {
				return nil, err
			}
			server.Token = &token
		}
		environment.KubernetesApiServer = server
	}
	if repositoryURL.Valid || repositoryUsername.Valid || repositoryPassword.Valid {
		repository := &ImageRepository{}
		if repositoryURL.Valid {
			repository.URL = &repositoryURL.String
		}
		if repositoryUsername.Valid {
			repository.Username = &repositoryUsername.String
		}
		if repositoryPassword.Valid && repositoryPassword.String != "" {
			password, err := s.codec.Decrypt(repositoryPassword.String)
			if err != nil {
				return nil, err
			}
			repository.Password = &password
		}
		environment.ImageRepository = repository
	}
	if gitCredential.Valid && gitCredential.String != "" {
		decrypted, err := s.codec.Decrypt(gitCredential.String)
		if err != nil {
			return nil, err
		}
		var credential GitCredential
		if err := json.Unmarshal([]byte(decrypted), &credential); err == nil {
			environment.GitCredential = &credential
		}
	}
	return &environment, nil
}

func (s *Store) ListEnvironmentsFull(ctx context.Context) ([]EnvironmentFull, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+environmentColumns+" FROM environment")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	environments := []EnvironmentFull{}
	for rows.Next() {
		environment, err := s.scanEnvironment(rows)
		if err != nil {
			return nil, err
		}
		environments = append(environments, *environment)
	}
	return environments, rows.Err()
}

func (s *Store) FindEnvironmentByID(ctx context.Context, id string) (*EnvironmentFull, error) {
	environment, err := s.scanEnvironment(s.db.QueryRowContext(ctx,
		"SELECT "+environmentColumns+" FROM environment WHERE id = ?", id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return environment, err
}

func (s *Store) FindEnvironmentFullByName(ctx context.Context, name string) (*EnvironmentFull, error) {
	environment, err := s.scanEnvironment(s.db.QueryRowContext(ctx,
		"SELECT "+environmentColumns+" FROM environment WHERE name = ? LIMIT 1", name))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return environment, err
}

func (s *Store) encryptOptional(value *string) (any, error) {
	if value == nil || *value == "" {
		return value, nil
	}
	encrypted, err := s.codec.Encrypt(*value)
	if err != nil {
		return nil, err
	}
	return encrypted, nil
}

func (s *Store) encryptGitCredential(credential *GitCredential) (any, error) {
	if credential.isEmpty() {
		return nil, nil
	}
	encoded, err := json.Marshal(credential)
	if err != nil {
		return nil, err
	}
	return s.codec.Encrypt(string(encoded))
}

func (s *Store) CreateEnvironment(ctx context.Context, environment *EnvironmentFull) (string, error) {
	if !IsValidEnvironmentName(environment.Name) {
		return "", bizErrorf("Invalid environment name: %s", environment.Name)
	}
	if s.environmentExists(ctx, environment.Name) {
		return "", bizErrorf("Environment already exists: %s", environment.Name)
	}
	var apiServerURL, token, repositoryURL, repositoryUsername *string
	if environment.KubernetesApiServer != nil {
		apiServerURL = environment.KubernetesApiServer.URL
		token = environment.KubernetesApiServer.Token
	}
	var repositoryPassword *string
	if environment.ImageRepository != nil {
		repositoryURL = environment.ImageRepository.URL
		repositoryUsername = environment.ImageRepository.Username
		repositoryPassword = environment.ImageRepository.Password
	}
	encryptedToken, err := s.encryptOptional(token)
	if err != nil {
		return "", err
	}
	encryptedPassword, err := s.encryptOptional(repositoryPassword)
	if err != nil {
		return "", err
	}
	encryptedGitCredential, err := s.encryptGitCredential(environment.GitCredential)
	if err != nil {
		return "", err
	}
	id := NewNanoID()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO environment (id, name, api_server_url, api_server_token, work_namespace,
		        build_storage_class, image_repository_url, image_repository_username,
		        image_repository_password, git_credential)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		id, environment.Name, apiServerURL, encryptedToken, environment.WorkNamespace,
		environment.BuildStorageClass, repositoryURL, repositoryUsername,
		encryptedPassword, encryptedGitCredential)
	return id, err
}

func (s *Store) UpdateEnvironmentClusterConfig(ctx context.Context, id string, server *KubernetesApiServer, workNamespace, buildStorageClass *string) error {
	var apiServerURL, token *string
	if server != nil {
		apiServerURL = server.URL
		token = server.Token
	}
	encryptedToken, err := s.encryptOptional(token)
	if err != nil {
		return err
	}
	result, err := s.db.ExecContext(ctx,
		`UPDATE environment SET api_server_url = ?, api_server_token = ?, work_namespace = ?, build_storage_class = ?
		 WHERE id = ?`,
		apiServerURL, encryptedToken, workNamespace, buildStorageClass, id)
	if err != nil {
		return err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		if _, err := s.FindEnvironmentByID(ctx, id); errors.Is(err, ErrNotFound) {
			return bizErrorf("Environment with id %s does not exist.", id)
		}
	}
	return nil
}

func (s *Store) UpdateEnvironmentCredentialConfig(ctx context.Context, id string, repository *ImageRepository, gitCredential *GitCredential) error {
	var repositoryURL, repositoryUsername, repositoryPassword *string
	if repository != nil {
		repositoryURL = repository.URL
		repositoryUsername = repository.Username
		repositoryPassword = repository.Password
	}
	encryptedPassword, err := s.encryptOptional(repositoryPassword)
	if err != nil {
		return err
	}
	encryptedGitCredential, err := s.encryptGitCredential(gitCredential)
	if err != nil {
		return err
	}
	result, err := s.db.ExecContext(ctx,
		`UPDATE environment SET image_repository_url = ?, image_repository_username = ?,
		        image_repository_password = ?, git_credential = ?
		 WHERE id = ?`,
		repositoryURL, repositoryUsername, encryptedPassword, encryptedGitCredential, id)
	if err != nil {
		return err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		if _, err := s.FindEnvironmentByID(ctx, id); errors.Is(err, ErrNotFound) {
			return bizErrorf("Environment with id %s does not exist.", id)
		}
	}
	return nil
}

func (s *Store) DeleteEnvironment(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM environment WHERE id = ?", id)
	return err
}
