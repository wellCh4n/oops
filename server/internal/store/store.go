// Package store is the MySQL persistence adapter, built on GORM. It reads the
// same schema the Java backend manages via Flyway; this process never runs
// migrations itself. The exported views keep the exact JSON shapes the Java
// DTOs serialize to.
package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// notFound translates GORM's sentinel to the store's own.
func notFound(err error) error {
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return ErrNotFound
	}
	return err
}

type Store struct {
	orm   *gorm.DB
	codec Codec
}

func Open(dsn string) (*Store, error) {
	orm, err := gorm.Open(mysql.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return nil, err
	}
	sqlDB, err := orm.DB()
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(10)
	sqlDB.SetMaxIdleConns(5)
	sqlDB.SetConnMaxLifetime(30 * time.Minute)
	if err := sqlDB.Ping(); err != nil {
		return nil, fmt.Errorf("ping mysql: %w", err)
	}
	return &Store{orm: orm}, nil
}

func (s *Store) Close() error {
	sqlDB, err := s.orm.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

type User struct {
	ID          string         `json:"id"`
	CreatedTime *LocalDateTime `json:"createdTime"`
	Username    string         `json:"username"`
	Email       string         `json:"email"`
	Password    string         `json:"-"`
	Role        string         `json:"role"`
	Enabled     bool           `json:"enabled"`
	AccessToken *string        `json:"accessToken"`
}

func (User) TableName() string { return "user" }

func (s *Store) FindUserByUsernameOrEmail(ctx context.Context, login string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).
		Where("username = ? OR email = ?", login, login).
		First(&user).Error
	return &user, notFound(err)
}

func (s *Store) FindUserByID(ctx context.Context, id string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).Where("id = ?", id).First(&user).Error
	return &user, notFound(err)
}

func (s *Store) UsernamesByIDs(ctx context.Context, ids []string) (map[string]string, error) {
	names := map[string]string{}
	if len(ids) == 0 {
		return names, nil
	}
	var users []User
	if err := s.orm.WithContext(ctx).
		Select("id", "username").
		Where("id IN ?", ids).
		Find(&users).Error; err != nil {
		return nil, err
	}
	for _, user := range users {
		names[user.ID] = user.Username
	}
	return names, nil
}

// ListUsers backs GET /api/users: the whole roster, for owner/collaborator pickers.
func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	users := []User{}
	err := s.orm.WithContext(ctx).Order("created_time").Find(&users).Error
	return users, err
}

// PageUsers backs GET /api/users/page: keyword filter over username/email.
func (s *Store) PageUsers(ctx context.Context, keyword string, page, size int) (int64, []User, error) {
	pattern := "%" + strings.ToLower(keyword) + "%"
	query := s.orm.WithContext(ctx).Model(&User{}).
		Where("LOWER(username) LIKE ? OR LOWER(email) LIKE ?", pattern, pattern)

	var total int64
	if err := query.Count(&total).Error; err != nil {
		return 0, nil, err
	}
	users := []User{}
	err := query.Order("created_time DESC").
		Limit(size).Offset((max(page, 1) - 1) * size).
		Find(&users).Error
	return total, users, err
}

type Application struct {
	ID          string         `json:"id"`
	CreatedTime *LocalDateTime `json:"createdTime"`
	Name        string         `json:"name"`
	Description *string        `json:"description"`
	Icon        *string        `json:"icon"`
	Namespace   string         `json:"namespace"`
	Owner       *string        `json:"owner"`
}

func (Application) TableName() string { return "application" }

// PageApplications mirrors ApplicationPersistenceAdapter: filter by namespace +
// keyword (and optionally owner), newest first. The Java side additionally
// orders by the viewer's latest publish; that refinement lands with the
// pipeline tables in a later migration step.
func (s *Store) PageApplications(ctx context.Context, namespace, keyword, ownerID string, page, size int) (int64, []Application, error) {
	query := s.orm.WithContext(ctx).Model(&Application{}).
		Where("namespace = ? AND LOWER(name) LIKE ?", namespace, "%"+strings.ToLower(keyword)+"%")
	if ownerID != "" {
		query = query.Where("owner = ?", ownerID)
	}
	var total int64
	if err := query.Count(&total).Error; err != nil {
		return 0, nil, err
	}
	applications := []Application{}
	err := query.Order("created_time DESC").
		Limit(size).Offset((max(page, 1) - 1) * size).
		Find(&applications).Error
	return total, applications, err
}

func (s *Store) SearchApplications(ctx context.Context, keyword string, size int) ([]Application, error) {
	applications := []Application{}
	err := s.orm.WithContext(ctx).
		Where("LOWER(name) LIKE ?", "%"+strings.ToLower(keyword)+"%").
		Order("created_time DESC").
		Limit(size).
		Find(&applications).Error
	return applications, err
}

// QueryApplications backs POST /api/index/applications.
func (s *Store) QueryApplications(ctx context.Context, namespace, name string) ([]Application, error) {
	query := s.orm.WithContext(ctx).Model(&Application{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}
	if name != "" {
		query = query.Where("name = ?", name)
	}
	applications := []Application{}
	err := query.Order("created_time DESC").Find(&applications).Error
	return applications, err
}

type applicationCollaboratorRecord struct {
	ID              string
	CreatedTime     *LocalDateTime
	Namespace       string
	ApplicationName string
	UserID          string `gorm:"column:user_id"`
}

func (applicationCollaboratorRecord) TableName() string { return "application_collaborator" }

// CollaboratorsByApplication returns userIds per "namespace/name" key.
func (s *Store) CollaboratorsByApplication(ctx context.Context, namespace string, names []string) (map[string][]string, error) {
	result := map[string][]string{}
	if len(names) == 0 {
		return result, nil
	}
	var rows []applicationCollaboratorRecord
	if err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name IN ?", namespace, names).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		key := namespace + "/" + row.ApplicationName
		result[key] = append(result[key], row.UserID)
	}
	return result, nil
}

// SourceTypesByApplication returns GIT/ZIP per "namespace/name" key.
func (s *Store) SourceTypesByApplication(ctx context.Context, namespace string, names []string) (map[string]string, error) {
	result := map[string]string{}
	if len(names) == 0 {
		return result, nil
	}
	var rows []buildConfigRecord
	if err := s.orm.WithContext(ctx).
		Select("application_name", "source_type").
		Where("namespace = ? AND application_name IN ?", namespace, names).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		if row.SourceType != nil && *row.SourceType != "" {
			result[namespace+"/"+row.ApplicationName] = *row.SourceType
		}
	}
	return result, nil
}
