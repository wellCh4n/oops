package store

import (
	"embed"

	"github.com/pressly/goose/v3"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Migrate applies the embedded goose migrations on startup, creating the
// schema on a fresh database and rolling forward any pending versions.
// Goose tracks state in its own goose_db_version table.
func (s *Store) Migrate() error {
	sqlDB, err := s.orm.DB()
	if err != nil {
		return err
	}
	goose.SetBaseFS(migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("mysql"); err != nil {
		return err
	}
	return goose.Up(sqlDB, "migrations")
}
