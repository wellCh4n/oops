package store

import (
	"context"
	"errors"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
)

// UserRepository owns the account table.
type UserRepository struct {
	store *Store
}

func userFromRow(row userRow) domain.User {
	return domain.User{
		ID:          row.ID,
		CreatedTime: row.CreatedTime,
		Username:    orNil(row.Username),
		Email:       orNil(row.Email),
		Password:    orNil(row.Password),
		Role:        enumOrNil[domain.UserRole](row.Role),
		AccessToken: ptrOf(row.AccessToken),
		Enabled:     domain.Ptr(row.Enabled),
	}
}

func usersFromRows(rows []userRow) []domain.User {
	result := make([]domain.User, 0, len(rows))
	for _, row := range rows {
		result = append(result, userFromRow(row))
	}
	return result
}

// ErrAmbiguousUser is returned when an identifier matches more than one
// account. Nothing stops two accounts sharing a username today, and resolving
// the ambiguity by picking a row would silently authenticate whoever asked as
// one of the two — so the lookup refuses instead, and both holders are locked
// out until an administrator renames one.
var ErrAmbiguousUser = errors.New("identifier matches more than one account")

// singleUser returns the only matching row, nil for none, and ErrAmbiguousUser
// for more than one.
func singleUser(rows []userRow, err error) (*domain.User, error) {
	if err != nil {
		return nil, err
	}
	switch len(rows) {
	case 0:
		return nil, nil
	case 1:
		user := userFromRow(rows[0])
		return &user, nil
	default:
		return nil, ErrAmbiguousUser
	}
}

// ---------------------------------------------------------------------------
// reads

// FindByID loads a user by primary key; nil when absent.
func (r *UserRepository) FindByID(ctx context.Context, id string) (*domain.User, error) {
	row, err := getOrNil[userRow](ctx, r.store.db, `SELECT * FROM user WHERE id = ? LIMIT 1`, id)
	if err != nil || row == nil {
		return nil, err
	}
	user := userFromRow(*row)
	return &user, nil
}

// FindByUsername loads a user by exact username; nil when absent.
func (r *UserRepository) FindByUsername(ctx context.Context, username string) (*domain.User, error) {
	return singleUser(list[userRow](ctx, r.store.db, `SELECT * FROM user WHERE username = ?`, username))
}

// FindByEmail loads a user by exact email; nil when absent.
func (r *UserRepository) FindByEmail(ctx context.Context, email string) (*domain.User, error) {
	return singleUser(list[userRow](ctx, r.store.db, `SELECT * FROM user WHERE email = ?`, email))
}

// FindByAccessToken loads the owner of an OpenAPI access token; nil when the
// token matches nobody.
func (r *UserRepository) FindByAccessToken(ctx context.Context, accessToken string) (*domain.User, error) {
	if strings.TrimSpace(accessToken) == "" {
		return nil, nil
	}
	return singleUser(list[userRow](ctx, r.store.db, `SELECT * FROM user WHERE access_token = ?`, nullIfEmpty(accessToken)))
}

// FindByUsernameOrEmail backs login, which accepts either identifier.
func (r *UserRepository) FindByUsernameOrEmail(ctx context.Context, identifier string) (*domain.User, error) {
	user, err := r.FindByUsername(ctx, identifier)
	if err != nil || user != nil {
		return user, err
	}
	return r.FindByEmail(ctx, identifier)
}

// FindAllByID loads the named users, for resolving owner names in bulk.
func (r *UserRepository) FindAllByID(ctx context.Context, ids []string) ([]domain.User, error) {
	if len(ids) == 0 {
		return []domain.User{}, nil
	}
	rows, err := listIn[userRow](ctx, r.store.db, `SELECT * FROM user WHERE id IN (?)`, ids)
	if err != nil {
		return nil, err
	}
	return usersFromRows(rows), nil
}

// FindAll returns every user.
func (r *UserRepository) FindAll(ctx context.Context) ([]domain.User, error) {
	rows, err := list[userRow](ctx, r.store.db, `SELECT * FROM user`)
	if err != nil {
		return nil, err
	}
	return usersFromRows(rows), nil
}

// userPageFilter matches a case-insensitive substring of username or email.
const userPageFilter = ` WHERE LOWER(username) LIKE LOWER(?) OR LOWER(email) LIKE LOWER(?)`

// FindPage is the paged user list, newest first.
func (r *UserRepository) FindPage(ctx context.Context, keyword string, page, size int) (Page[domain.User], error) {
	pattern := "%" + keyword + "%"
	total, err := count(ctx, r.store.db, `SELECT COUNT(*) FROM user`+userPageFilter, pattern, pattern)
	if err != nil {
		return Page[domain.User]{}, err
	}
	limit, offset := pageWindow(page, size)
	rows, err := list[userRow](ctx, r.store.db,
		`SELECT * FROM user`+userPageFilter+` ORDER BY created_time DESC LIMIT ? OFFSET ?`,
		pattern, pattern, limit, offset)
	if err != nil {
		return Page[domain.User]{}, err
	}
	return newPage(total, usersFromRows(rows), size), nil
}

// ExistsByRole backs the "is there an admin yet?" question the initializer asks.
func (r *UserRepository) ExistsByRole(ctx context.Context, role domain.UserRole) (bool, error) {
	return exists(ctx, r.store.db, `SELECT 1 FROM user WHERE role = ? LIMIT 1`, string(role))
}

// CountEnabledByRole guards the last enabled admin against being disabled.
func (r *UserRepository) CountEnabledByRole(ctx context.Context, role domain.UserRole) (int64, error) {
	return count(ctx, r.store.db, `SELECT COUNT(*) FROM user WHERE role = ? AND enabled = TRUE`, string(role))
}

// ---------------------------------------------------------------------------
// writes

// Save inserts or updates a user. A duplicate access token surfaces as
// ErrDuplicate.
func (r *UserRepository) Save(ctx context.Context, user *domain.User) (*domain.User, error) {
	found := false
	if user.ID != "" {
		var err error
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM user WHERE id = ? LIMIT 1`, user.ID); err != nil {
			return nil, err
		}
	}
	enabled := user.Enabled == nil || *user.Enabled
	if found {
		if _, err := execRows(ctx, r.store.db,
			`UPDATE user
SET created_time = ?, email = ?, password = ?, role = ?, username = ?, access_token = ?, enabled = ?
WHERE id = ?`,
			user.CreatedTime, domain.Deref(user.Email), domain.Deref(user.Password), enumName(user.Role),
			domain.Deref(user.Username), nullString(user.AccessToken), enabled, user.ID); err != nil {
			return nil, err
		}
		return user, nil
	}
	user.ID = ensureID(user.ID)
	if user.CreatedTime.IsZero() {
		user.CreatedTime = domain.Now()
	}
	if err := exec(ctx, r.store.db,
		`INSERT INTO user (id, created_time, email, password, role, username, access_token, enabled)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		user.ID, user.CreatedTime, domain.Deref(user.Email), domain.Deref(user.Password), enumName(user.Role),
		domain.Deref(user.Username), nullString(user.AccessToken), enabled); err != nil {
		return nil, err
	}
	return user, nil
}

// DeleteByID removes a user.
func (r *UserRepository) DeleteByID(ctx context.Context, id string) error {
	_, err := execRows(ctx, r.store.db, `DELETE FROM user WHERE id = ?`, id)
	return err
}
