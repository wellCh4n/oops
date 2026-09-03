// Package store is the MySQL persistence layer. It works in domain types and
// owns every SQL statement in the product: NanoId primary keys, naive local
// wall-clock timestamps, enum names as strings, Jackson-shaped JSON blobs and
// AES-encrypted secret columns.
//
// The SQL is written inline, next to the method that runs it, and rows are
// scanned into the small db-tagged structs in rows.go. There is no code
// generation step: what you read is what runs.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"

	"github.com/go-sql-driver/mysql"
	"github.com/jmoiron/sqlx"

	"github.com/wellch4n/oops/server/internal/domain"
)

// ErrDuplicate is returned when an INSERT or UPDATE violates a unique key
// (MySQL error 1062), e.g. a second application with the same name.
var ErrDuplicate = errors.New("duplicate key")

const mysqlDuplicateEntry = 1062

// queryer is the half of sqlx's API the repositories use. Every helper takes
// one so a statement can run either on the pool or inside a transaction
// without being written twice.
type queryer interface {
	sqlx.ExecerContext
	GetContext(ctx context.Context, dest any, query string, args ...any) error
	SelectContext(ctx context.Context, dest any, query string, args ...any) error
}

// Store is the root of the persistence API. Obtain repositories through the
// grouped accessors (Applications(), Pipelines(), ...).
type Store struct {
	db *sqlx.DB
}

// Open connects to MySQL with the given DSN, sets the pool size and pings.
// The DSN must carry parseTime=true&loc=Local so datetime(6) columns arrive
// as local wall-clock times.
func Open(dsn string, maxOpen int) (*sqlx.DB, error) {
	db, err := sqlx.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("open mysql: %w", err)
	}
	if maxOpen > 0 {
		db.SetMaxOpenConns(maxOpen)
		db.SetMaxIdleConns(maxOpen)
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping mysql: %w", err)
	}
	return db, nil
}

// New wraps an open database. The secret columns encrypt themselves through
// the codec installed with crypto.SetDefault, so nothing is passed in here.
func New(db *sqlx.DB) *Store {
	return &Store{db: db}
}

// DB exposes the underlying pool (for health checks and migrations).
func (s *Store) DB() *sqlx.DB { return s.db }

func (s *Store) Applications() *ApplicationRepository   { return &ApplicationRepository{store: s} }
func (s *Store) Pipelines() *PipelineRepository         { return &PipelineRepository{store: s} }
func (s *Store) Users() *UserRepository                 { return &UserRepository{store: s} }
func (s *Store) Environments() *EnvironmentRepository   { return &EnvironmentRepository{store: s} }
func (s *Store) Domains() *DomainRepository             { return &DomainRepository{store: s} }
func (s *Store) Namespaces() *NamespaceRepository       { return &NamespaceRepository{store: s} }
func (s *Store) ExternalAccounts() *ExternalAccountRepo { return &ExternalAccountRepo{store: s} }
func (s *Store) AlertStates() *AlertStateRepository     { return &AlertStateRepository{store: s} }

// Page is a 1-based page of results; TotalPages = ceil(Total / Size).
type Page[T any] struct {
	Total      int64
	Data       []T
	Size       int
	TotalPages int
}

func newPage[T any](total int64, data []T, size int) Page[T] {
	if data == nil {
		data = []T{}
	}
	totalPages := 0
	if size > 0 {
		totalPages = int(math.Ceil(float64(total) / float64(size)))
	}
	return Page[T]{Total: total, Data: data, Size: size, TotalPages: totalPages}
}

// pageWindow converts a 1-based page into LIMIT/OFFSET.
func pageWindow(page, size int) (limit int, offset int) {
	if page < 1 {
		page = 1
	}
	if size < 0 {
		size = 0
	}
	return size, (page - 1) * size
}

// withTx runs fn inside a transaction, committing on nil and rolling back on
// error or panic.
func (s *Store) withTx(ctx context.Context, fn func(tx queryer) error) (err error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			_ = tx.Rollback()
			panic(recovered)
		}
		if err != nil {
			_ = tx.Rollback()
			return
		}
		err = tx.Commit()
	}()
	return fn(tx)
}

// ---------------------------------------------------------------------------
// query helpers

// getOrNil reads one row, turning "no rows" into a nil result rather than an
// error — most lookups here treat absence as an ordinary answer.
func getOrNil[T any](ctx context.Context, q queryer, query string, args ...any) (*T, error) {
	var row T
	err := q.GetContext(ctx, &row, query, args...)
	if isNoRows(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &row, nil
}

// list reads every matching row.
func list[T any](ctx context.Context, q queryer, query string, args ...any) ([]T, error) {
	rows := []T{}
	if err := q.SelectContext(ctx, &rows, query, args...); err != nil {
		return nil, err
	}
	return rows, nil
}

// listIn runs a query containing `IN (?)` against slice arguments, expanding
// each into the right number of bind variables.
func listIn[T any](ctx context.Context, q queryer, query string, args ...any) ([]T, error) {
	expanded, expandedArgs, err := sqlx.In(query, args...)
	if err != nil {
		return nil, err
	}
	return list[T](ctx, q, expanded, expandedArgs...)
}

// exists reports whether a `SELECT 1 ... LIMIT 1` matched.
func exists(ctx context.Context, q queryer, query string, args ...any) (bool, error) {
	var one int
	err := q.GetContext(ctx, &one, query, args...)
	if isNoRows(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

// existsIn is exists for a query with an `IN (?)` placeholder.
func existsIn(ctx context.Context, q queryer, query string, args ...any) (bool, error) {
	expanded, expandedArgs, err := sqlx.In(query, args...)
	if err != nil {
		return false, err
	}
	return exists(ctx, q, expanded, expandedArgs...)
}

// count runs a `SELECT COUNT(*)`.
func count(ctx context.Context, q queryer, query string, args ...any) (int64, error) {
	var total int64
	err := q.GetContext(ctx, &total, query, args...)
	return total, err
}

// execRows runs a statement and reports how many rows it changed. The count is
// what the conditional pipeline updates use as an optimistic lock, so it is
// returned rather than discarded.
func execRows(ctx context.Context, q queryer, query string, args ...any) (int64, error) {
	result, err := q.ExecContext(ctx, query, args...)
	if err != nil {
		return 0, translateError(err)
	}
	return result.RowsAffected()
}

// exec runs a statement whose row count means nothing.
func exec(ctx context.Context, q queryer, query string, args ...any) error {
	_, err := q.ExecContext(ctx, query, args...)
	return translateError(err)
}

// translateError maps driver errors to package sentinels.
func translateError(err error) error {
	if err == nil {
		return nil
	}
	var mysqlError *mysql.MySQLError
	if errors.As(err, &mysqlError) && mysqlError.Number == mysqlDuplicateEntry {
		return fmt.Errorf("%w: %s", ErrDuplicate, mysqlError.Message)
	}
	return err
}

// isNoRows reports whether err is sql.ErrNoRows.
func isNoRows(err error) bool { return errors.Is(err, sql.ErrNoRows) }

// ---------------------------------------------------------------------------
// column helpers
//
// The schema is the Java backend's, so nearly every column is nullable and the
// row structs carry sql.Null* to match. These turn that into the domain's own
// vocabulary — a nil pointer for an unset value — so nothing above this package
// deals in sql.Null*.

func ptrOf(value sql.NullString) *string {
	if !value.Valid {
		return nil
	}
	text := value.String
	return &text
}

// stringOf reads a nullable column into a domain field that is a plain string,
// where NULL and "" mean the same thing to the caller.
func stringOf(value sql.NullString) string {
	if !value.Valid {
		return ""
	}
	return value.String
}

func nullString(value *string) sql.NullString {
	if value == nil {
		return sql.NullString{}
	}
	return sql.NullString{String: *value, Valid: true}
}

// nullIfEmpty stores "" as NULL. Used for the columns the Java backend left
// NULL rather than blank, so a row written here is indistinguishable from one
// written by 2.x.
func nullIfEmpty(value string) sql.NullString {
	if value == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: value, Valid: true}
}

// enumPtrOf reads a nullable enum column.
func enumPtrOf[T ~string](value sql.NullString) *T {
	if !value.Valid {
		return nil
	}
	result := T(value.String)
	return &result
}

// enumString writes an optional enum, storing NULL when it is unset.
func enumString[T ~string](value *T) sql.NullString {
	if value == nil {
		return sql.NullString{}
	}
	return sql.NullString{String: string(*value), Valid: true}
}

func nullBool(value *bool) sql.NullBool {
	if value == nil {
		return sql.NullBool{}
	}
	return sql.NullBool{Bool: *value, Valid: true}
}

func boolPtrOf(value sql.NullBool) *bool {
	if !value.Valid {
		return nil
	}
	result := value.Bool
	return &result
}

func nullInt(value *int) sql.NullInt32 {
	if value == nil {
		return sql.NullInt32{}
	}
	return sql.NullInt32{Int32: int32(*value), Valid: true}
}

func intPtrOf(value sql.NullInt32) *int {
	if !value.Valid {
		return nil
	}
	result := int(value.Int32)
	return &result
}

// ensureID mirrors the old @PrePersist: an empty id gets a fresh NanoId.
func ensureID(id string) string {
	if id == "" {
		return domain.NewID()
	}
	return id
}
