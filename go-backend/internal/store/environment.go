package store

import (
	"context"
	"database/sql"
)

// EnvironmentView mirrors EnvironmentDto. Secrets (API server token, registry
// password, git credential) stay redacted until the crypto port lands — the
// Java side stores the token AES-encrypted with oops.crypto.secret-key.
type EnvironmentView struct {
	ID                  string               `json:"id"`
	Name                string               `json:"name"`
	KubernetesApiServer *KubernetesApiServer `json:"kubernetesApiServer"`
	WorkNamespace       *string              `json:"workNamespace"`
	BuildStorageClass   *string              `json:"buildStorageClass"`
	ImageRepository     *ImageRepository     `json:"imageRepository"`
	GitCredential       *struct{}            `json:"gitCredential"`
}

type KubernetesApiServer struct {
	URL   *string `json:"url"`
	Token *string `json:"token"`
}

type ImageRepository struct {
	URL      *string `json:"url"`
	Username *string `json:"username"`
	Password *string `json:"password"`
}

// EnvironmentCredentials carries what a cluster client needs; Token is still
// encrypted at rest and must go through the crypto codec before use.
type EnvironmentCredentials struct {
	APIServerURL string
	Token        string
}

func (s *Store) FindEnvironmentCredentials(ctx context.Context, name string) (*EnvironmentCredentials, error) {
	var credentials EnvironmentCredentials
	var url, token sql.NullString
	err := s.db.QueryRowContext(ctx,
		"SELECT api_server_url, api_server_token FROM environment WHERE name = ?", name).
		Scan(&url, &token)
	if err != nil {
		return nil, err
	}
	credentials.APIServerURL = url.String
	credentials.Token = token.String
	return &credentials, nil
}

func (s *Store) ListEnvironments(ctx context.Context) ([]EnvironmentView, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, api_server_url, work_namespace, build_storage_class,
		        image_repository_url, image_repository_username
		 FROM environment ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	environments := []EnvironmentView{}
	for rows.Next() {
		var environment EnvironmentView
		var apiServerURL, imageRepositoryURL, imageRepositoryUsername sql.NullString
		if err := rows.Scan(&environment.ID, &environment.Name, &apiServerURL,
			&environment.WorkNamespace, &environment.BuildStorageClass,
			&imageRepositoryURL, &imageRepositoryUsername); err != nil {
			return nil, err
		}
		if apiServerURL.Valid {
			environment.KubernetesApiServer = &KubernetesApiServer{URL: &apiServerURL.String}
		}
		if imageRepositoryURL.Valid || imageRepositoryUsername.Valid {
			repository := &ImageRepository{}
			if imageRepositoryURL.Valid {
				repository.URL = &imageRepositoryURL.String
			}
			if imageRepositoryUsername.Valid {
				repository.Username = &imageRepositoryUsername.String
			}
			environment.ImageRepository = repository
		}
		environments = append(environments, environment)
	}
	return environments, rows.Err()
}
