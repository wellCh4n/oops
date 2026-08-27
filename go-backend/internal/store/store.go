// Package store is the MySQL persistence adapter. It reads the same schema the
// Java backend manages via Flyway; this process never runs migrations itself.
package store

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

type Store struct {
	db *sql.DB
}

func Open(dsn string) (*Store, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping mysql: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// columnExists reports whether a column is present in the connected schema.
func (s *Store) columnExists(ctx context.Context, table, column string) bool {
	var count int
	err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM information_schema.columns
		 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?`,
		table, column).Scan(&count)
	return err == nil && count > 0
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

const userColumns = "id, created_time, username, email, password, role, enabled, access_token"

func scanUser(row *sql.Row) (*User, error) {
	var user User
	var password sql.NullString
	err := row.Scan(&user.ID, &user.CreatedTime, &user.Username, &user.Email,
		&password, &user.Role, &user.Enabled, &user.AccessToken)
	if err != nil {
		return nil, err
	}
	user.Password = password.String
	return &user, nil
}

func (s *Store) FindUserByUsernameOrEmail(ctx context.Context, login string) (*User, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+userColumns+" FROM user WHERE username = ? OR email = ? LIMIT 1", login, login)
	return scanUser(row)
}

func (s *Store) FindUserByID(ctx context.Context, id string) (*User, error) {
	row := s.db.QueryRowContext(ctx, "SELECT "+userColumns+" FROM user WHERE id = ?", id)
	return scanUser(row)
}

func (s *Store) UsernamesByIDs(ctx context.Context, ids []string) (map[string]string, error) {
	names := map[string]string{}
	if len(ids) == 0 {
		return names, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT id, username FROM user WHERE id IN ("+placeholders+")", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, username string
		if err := rows.Scan(&id, &username); err != nil {
			return nil, err
		}
		names[id] = username
	}
	return names, rows.Err()
}

// PageUsers backs GET /api/users/page: keyword filter over username/email.
func (s *Store) PageUsers(ctx context.Context, keyword string, page, size int) (int64, []User, error) {
	where := "LOWER(username) LIKE ? OR LOWER(email) LIKE ?"
	pattern := "%" + strings.ToLower(keyword) + "%"

	var total int64
	if err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM user WHERE "+where, pattern, pattern).Scan(&total); err != nil {
		return 0, nil, err
	}

	offset := (max(page, 1) - 1) * size
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+userColumns+" FROM user WHERE "+where+" ORDER BY created_time LIMIT ? OFFSET ?",
		pattern, pattern, size, offset)
	if err != nil {
		return 0, nil, err
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		var user User
		var password sql.NullString
		if err := rows.Scan(&user.ID, &user.CreatedTime, &user.Username, &user.Email,
			&password, &user.Role, &user.Enabled, &user.AccessToken); err != nil {
			return 0, nil, err
		}
		users = append(users, user)
	}
	return total, users, rows.Err()
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

const applicationColumns = "id, created_time, name, description, icon, namespace, owner"

func scanApplications(rows *sql.Rows) ([]Application, error) {
	var applications []Application
	for rows.Next() {
		var application Application
		if err := rows.Scan(&application.ID, &application.CreatedTime, &application.Name,
			&application.Description, &application.Icon, &application.Namespace, &application.Owner); err != nil {
			return nil, err
		}
		applications = append(applications, application)
	}
	return applications, rows.Err()
}

// PageApplications mirrors ApplicationPersistenceAdapter: filter by namespace +
// keyword (and optionally owner), newest first. The Java side additionally
// orders by the viewer's latest publish; that refinement lands with the
// pipeline tables in a later migration step.
func (s *Store) PageApplications(ctx context.Context, namespace, keyword, ownerID string, page, size int) (int64, []Application, error) {
	where := "namespace = ? AND LOWER(name) LIKE ?"
	args := []any{namespace, "%" + strings.ToLower(keyword) + "%"}
	if ownerID != "" {
		where += " AND owner = ?"
		args = append(args, ownerID)
	}

	var total int64
	if err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM application WHERE "+where, args...).Scan(&total); err != nil {
		return 0, nil, err
	}

	offset := (max(page, 1) - 1) * size
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+applicationColumns+" FROM application WHERE "+where+
			" ORDER BY created_time DESC LIMIT ? OFFSET ?",
		append(args, size, offset)...)
	if err != nil {
		return 0, nil, err
	}
	defer rows.Close()
	applications, err := scanApplications(rows)
	return total, applications, err
}

func (s *Store) SearchApplications(ctx context.Context, keyword string, size int) ([]Application, error) {
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+applicationColumns+" FROM application WHERE LOWER(name) LIKE ? ORDER BY created_time DESC LIMIT ?",
		"%"+strings.ToLower(keyword)+"%", size)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanApplications(rows)
}

// CollaboratorsByApplication returns userIds per "namespace/name" key.
func (s *Store) CollaboratorsByApplication(ctx context.Context, namespace string, names []string) (map[string][]string, error) {
	result := map[string][]string{}
	if len(names) == 0 {
		return result, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(names)), ",")
	args := []any{namespace}
	for _, name := range names {
		args = append(args, name)
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT application_name, user_id FROM application_collaborator WHERE namespace = ? AND application_name IN ("+placeholders+")",
		args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var applicationName, userID string
		if err := rows.Scan(&applicationName, &userID); err != nil {
			return nil, err
		}
		key := namespace + "/" + applicationName
		result[key] = append(result[key], userID)
	}
	return result, rows.Err()
}

// SourceTypesByApplication returns GIT/ZIP per "namespace/name" key.
func (s *Store) SourceTypesByApplication(ctx context.Context, namespace string, names []string) (map[string]string, error) {
	result := map[string]string{}
	if len(names) == 0 {
		return result, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(names)), ",")
	args := []any{namespace}
	for _, name := range names {
		args = append(args, name)
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT application_name, source_type FROM application_build_config WHERE namespace = ? AND application_name IN ("+placeholders+")",
		args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var applicationName string
		var sourceType sql.NullString
		if err := rows.Scan(&applicationName, &sourceType); err != nil {
			return nil, err
		}
		if sourceType.Valid {
			result[namespace+"/"+applicationName] = sourceType.String
		}
	}
	return result, rows.Err()
}
