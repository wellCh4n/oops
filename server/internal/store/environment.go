package store

import "context"

// environmentRecord is the GORM model of the environment table; token,
// registry password and the git credential JSON are encrypted at rest.
type environmentRecord struct {
	ID                      string
	Name                    string
	APIServerURL            *string `gorm:"column:api_server_url"`
	APIServerToken          *string `gorm:"column:api_server_token"`
	WorkNamespace           *string
	BuildStorageClass       *string
	ImageRepositoryURL      *string `gorm:"column:image_repository_url"`
	ImageRepositoryUsername *string `gorm:"column:image_repository_username"`
	ImageRepositoryPassword *string `gorm:"column:image_repository_password"`
	GitCredential           *string `gorm:"column:git_credential"`
}

func (environmentRecord) TableName() string { return "environment" }

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
	var record environmentRecord
	err := s.orm.WithContext(ctx).
		Select("api_server_url", "api_server_token").
		Where("name = ?", name).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	credentials := &EnvironmentCredentials{}
	if record.APIServerURL != nil {
		credentials.APIServerURL = *record.APIServerURL
	}
	if record.APIServerToken != nil {
		credentials.Token = *record.APIServerToken
	}
	return credentials, nil
}
