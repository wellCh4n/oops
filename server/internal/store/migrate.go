package store

import (
	"database/sql"
	"embed"
	"errors"
	"fmt"

	"github.com/pressly/goose/v3"
)

//go:embed migrations/*.sql
var migrations embed.FS

// baselineFlywayVersion is the Java-side Flyway version the goose baseline
// (00001) was dumped from. A database migrated by an older Java release is
// missing columns the baseline's IF NOT EXISTS statements cannot add, so it
// must finish the Java migrations first.
const baselineFlywayVersion = 21

// Migrate applies the embedded goose migrations on startup, creating the
// schema on a fresh database and rolling forward any pending versions.
// Goose tracks state in its own goose_db_version table.
func (s *Store) Migrate() error {
	sqlDB, err := s.orm.DB()
	if err != nil {
		return err
	}
	if err := ensureFlywayAtBaseline(sqlDB); err != nil {
		return err
	}
	goose.SetBaseFS(migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("mysql"); err != nil {
		return err
	}
	return goose.Up(sqlDB, "migrations")
}

// ensureFlywayAtBaseline guards the 2.x -> 3.x upgrade path: a database whose
// flyway_schema_history stopped before the baseline version came from an old
// Java release and lacks later columns, so starting would corrupt nothing but
// fail confusingly. Fresh databases (no flyway table) and databases already
// managed by goose pass through.
func ensureFlywayAtBaseline(sqlDB *sql.DB) error {
	var gooseVersion int
	if err := sqlDB.QueryRow("SELECT MAX(version_id) FROM goose_db_version").Scan(&gooseVersion); err == nil {
		return nil // already on goose; flyway history no longer matters
	}

	var flywayVersion sql.NullInt64
	err := sqlDB.QueryRow(
		"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1").Scan(&flywayVersion)
	if err != nil {
		return nil // no flyway history: a fresh database, the baseline builds it
	}
	if flywayVersion.Valid && flywayVersion.Int64 < baselineFlywayVersion {
		return errors.New(upgradePathMessage(flywayVersion.Int64))
	}
	return nil
}

func upgradePathMessage(current int64) string {
	return fmt.Sprintf(
		"database schema is at Java migration V%d but this release expects at least V%d: "+
			"upgrade to the latest 2.x release first so its migrations complete, then upgrade to 3.x",
		current, baselineFlywayVersion)
}

// MigrateDown rolls back the most recent goose migration — the operator's
// escape hatch when a release must be rolled back past a schema change
// (run with the -migrate-down flag before starting the previous release).
func (s *Store) MigrateDown() error {
	sqlDB, err := s.orm.DB()
	if err != nil {
		return err
	}
	goose.SetBaseFS(migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("mysql"); err != nil {
		return err
	}
	return goose.Down(sqlDB, "migrations")
}
