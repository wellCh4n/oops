package store

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"log/slog"

	"github.com/jmoiron/sqlx"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/database"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrate creates the schema on an empty database and is a no-op on one that
// already has it. There is exactly one migration — OOPS 4.0 is a new install
// with no earlier database to migrate from — so this never replays a history.
func Migrate(ctx context.Context, db *sqlx.DB) error {
	migrations, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		return err
	}
	provider, err := goose.NewProvider(database.DialectMySQL, db.DB, migrations,
		goose.WithVerbose(false),
		goose.WithSlog(slog.Default()),
	)
	if err != nil {
		return fmt.Errorf("create goose provider: %w", err)
	}
	results, err := provider.Up(ctx)
	if err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	for _, result := range results {
		slog.Info("applied migration", "version", result.Source.Version, "path", result.Source.Path, "duration", result.Duration)
	}
	version, err := provider.GetDBVersion(ctx)
	if err != nil {
		return err
	}
	slog.Info("database schema is up to date", "version", version)
	return nil
}
