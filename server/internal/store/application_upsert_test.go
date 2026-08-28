package store

import (
	"context"
	"strings"
	"testing"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// dryRunORM builds a GORM handle that renders SQL without touching a database:
// DryRun skips execution, and the default transaction has to go too because
// BEGIN would still need a live connection.
func dryRunORM(t *testing.T) *gorm.DB {
	t.Helper()
	orm, err := gorm.Open(
		mysql.New(mysql.Config{
			DSN:                       "user:password@tcp(127.0.0.1:1)/oops?parseTime=true",
			SkipInitializeWithVersion: true,
		}),
		&gorm.Config{
			DryRun:                 true,
			DisableAutomaticPing:   true,
			SkipDefaultTransaction: true,
			Logger:                 logger.Default.LogMode(logger.Silent),
		})
	if err != nil {
		t.Fatalf("open dry-run orm: %v", err)
	}
	return orm
}

// The update model must stay a zero value. GORM folds a non-zero primary key
// on the model into the WHERE clause, so passing the freshly minted record
// pinned the statement to an id no row carries: every config save after the
// first insert updated nothing and reported success.
func TestUpsertRecordUpdateIsNotPinnedToTheFreshID(t *testing.T) {
	orm := dryRunORM(t)
	var updateSQL string
	err := orm.Callback().Update().After("gorm:update").
		Register("test:capture_update_sql", func(tx *gorm.DB) {
			updateSQL = tx.Statement.SQL.String()
		})
	if err != nil {
		t.Fatalf("register callback: %v", err)
	}

	// DryRun never scans rows, so the existence check reports no error and
	// upsertRecord takes the update branch.
	record := runtimeSpecRecord{
		ID: "freshidfreshidfreshidfre", CreatedTime: Now(),
		Namespace: "temp", ApplicationName: "htmlupload",
		EnvironmentConfigs: jsonOf([]RuntimeEnvironmentConfig{}),
	}
	upsertErr := upsertRecord(context.Background(), orm, "temp", "htmlupload", &record,
		map[string]any{"environment_configs": record.EnvironmentConfigs})
	if upsertErr != nil {
		t.Fatalf("upsertRecord: %v", upsertErr)
	}

	if updateSQL == "" {
		t.Fatal("no UPDATE statement was built")
	}
	if strings.Contains(updateSQL, "`id`") {
		t.Fatalf("update pinned to the record id, it would match no row: %s", updateSQL)
	}
	want := "UPDATE `application_runtime_spec` SET `environment_configs`=? WHERE namespace = ? AND application_name = ?"
	if updateSQL != want {
		t.Fatalf("unexpected update statement:\n got: %s\nwant: %s", updateSQL, want)
	}
}
