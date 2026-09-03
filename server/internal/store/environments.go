package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"github.com/wellch4n/oops/server/internal/domain"
)

// EnvironmentRepository owns the environment table.
//
// Three columns are secrets at rest and pass through the codec on the way in
// and out: the Kubernetes API server token, the image registry password, and
// the whole git-credential blob, which is encrypted after serialising rather
// than per field.
type EnvironmentRepository struct {
	store *Store
}

func (r *EnvironmentRepository) environmentFromRow(row environmentRow) (*domain.Environment, error) {
	token, err := r.decryptColumn(row.APIServerToken, "api_server_token", row.ID)
	if err != nil {
		return nil, err
	}
	password, err := r.decryptColumn(row.ImageRepositoryPassword, "image_repository_password", row.ID)
	if err != nil {
		return nil, err
	}
	gitCredential, err := r.decodeGitCredential(row.GitCredential, row.ID)
	if err != nil {
		return nil, err
	}
	return &domain.Environment{
		ID:                  row.ID,
		Name:                stringOf(row.Name),
		KubernetesApiServer: &domain.KubernetesApiServer{URL: ptrOf(row.APIServerURL), Token: token},
		WorkNamespace:       ptrOf(row.WorkNamespace),
		BuildStorageClass:   ptrOf(row.BuildStorageClass),
		ImageRepository: &domain.ImageRepository{
			URL:      ptrOf(row.ImageRepositoryURL),
			Username: ptrOf(row.ImageRepositoryUsername),
			Password: password,
		},
		GitCredential: gitCredential,
	}, nil
}

func (r *EnvironmentRepository) decryptColumn(column sql.NullString, name, id string) (*string, error) {
	if !column.Valid || column.String == "" {
		return nil, nil
	}
	plain, err := r.store.codec.Decrypt(column.String)
	if err != nil {
		return nil, fmt.Errorf("environment %s %s: %w", id, name, err)
	}
	return &plain, nil
}

func (r *EnvironmentRepository) encryptColumn(value *string) (sql.NullString, error) {
	if value == nil {
		return sql.NullString{}, nil
	}
	cipher, err := r.store.codec.Encrypt(*value)
	if err != nil {
		return sql.NullString{}, err
	}
	return sql.NullString{String: cipher, Valid: true}, nil
}

func (r *EnvironmentRepository) decodeGitCredential(column sql.NullString, id string) (*domain.GitCredential, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	plain, err := r.store.codec.Decrypt(column.String)
	if err != nil {
		return nil, fmt.Errorf("environment %s git_credential: %w", id, err)
	}
	var credential domain.GitCredential
	if err := json.Unmarshal([]byte(plain), &credential); err != nil {
		return nil, fmt.Errorf("environment %s git_credential: %w", id, err)
	}
	return &credential, nil
}

func (r *EnvironmentRepository) encodeGitCredential(credential *domain.GitCredential) (sql.NullString, error) {
	if credential == nil {
		return sql.NullString{}, nil
	}
	plain, err := json.Marshal(credential)
	if err != nil {
		return sql.NullString{}, err
	}
	cipher, err := r.store.codec.Encrypt(string(plain))
	if err != nil {
		return sql.NullString{}, err
	}
	return sql.NullString{String: cipher, Valid: true}, nil
}

// ---------------------------------------------------------------------------
// reads

// FindAll returns every environment.
func (r *EnvironmentRepository) FindAll(ctx context.Context) ([]domain.Environment, error) {
	rows, err := list[environmentRow](ctx, r.store.db, `SELECT * FROM environment`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.Environment, 0, len(rows))
	for _, row := range rows {
		environment, err := r.environmentFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *environment)
	}
	return result, nil
}

// FindByName loads an environment by its name — the reference every other table
// stores; nil when absent.
func (r *EnvironmentRepository) FindByName(ctx context.Context, name string) (*domain.Environment, error) {
	row, err := getOrNil[environmentRow](ctx, r.store.db, `SELECT * FROM environment WHERE name = ? LIMIT 1`, name)
	if err != nil || row == nil {
		return nil, err
	}
	return r.environmentFromRow(*row)
}

// FindByID loads an environment by primary key; nil when absent.
func (r *EnvironmentRepository) FindByID(ctx context.Context, id string) (*domain.Environment, error) {
	row, err := getOrNil[environmentRow](ctx, r.store.db, `SELECT * FROM environment WHERE id = ? LIMIT 1`, id)
	if err != nil || row == nil {
		return nil, err
	}
	return r.environmentFromRow(*row)
}

// ---------------------------------------------------------------------------
// writes

// Save inserts or updates an environment, encrypting the secret columns. A
// duplicate name surfaces as ErrDuplicate.
func (r *EnvironmentRepository) Save(ctx context.Context, environment *domain.Environment) (*domain.Environment, error) {
	apiServer := environment.KubernetesApiServer
	if apiServer == nil {
		apiServer = &domain.KubernetesApiServer{}
	}
	imageRepository := environment.ImageRepository
	if imageRepository == nil {
		imageRepository = &domain.ImageRepository{}
	}
	token, err := r.encryptColumn(apiServer.Token)
	if err != nil {
		return nil, err
	}
	password, err := r.encryptColumn(imageRepository.Password)
	if err != nil {
		return nil, err
	}
	gitCredential, err := r.encodeGitCredential(environment.GitCredential)
	if err != nil {
		return nil, err
	}

	found := false
	if environment.ID != "" {
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM environment WHERE id = ? LIMIT 1`, environment.ID); err != nil {
			return nil, err
		}
	}
	if found {
		_, err = execRows(ctx, r.store.db,
			`UPDATE environment
SET build_storage_class = ?, image_repository_password = ?, image_repository_url = ?,
    image_repository_username = ?, api_server_token = ?, api_server_url = ?, name = ?,
    work_namespace = ?, git_credential = ?
WHERE id = ?`,
			nullString(environment.BuildStorageClass), password, nullString(imageRepository.URL),
			nullString(imageRepository.Username), token, nullString(apiServer.URL), environment.Name,
			nullString(environment.WorkNamespace), gitCredential, environment.ID)
	} else {
		environment.ID = ensureID(environment.ID)
		err = exec(ctx, r.store.db,
			`INSERT INTO environment
(id, build_storage_class, image_repository_password, image_repository_url, image_repository_username,
 api_server_token, api_server_url, name, work_namespace, git_credential)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			environment.ID, nullString(environment.BuildStorageClass), password, nullString(imageRepository.URL),
			nullString(imageRepository.Username), token, nullString(apiServer.URL), environment.Name,
			nullString(environment.WorkNamespace), gitCredential)
	}
	if err != nil {
		return nil, err
	}
	return environment, nil
}

// DeleteByID removes an environment.
func (r *EnvironmentRepository) DeleteByID(ctx context.Context, id string) error {
	_, err := execRows(ctx, r.store.db, `DELETE FROM environment WHERE id = ?`, id)
	return err
}
