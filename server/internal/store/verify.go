package store

import (
	"context"
	"fmt"
	"reflect"
	"sort"
	"strings"
)

// VerifySchema checks that the live database matches what the row structs
// expect, and refuses to run against one that does not.
//
// It exists because the migration is `CREATE TABLE IF NOT EXISTS`: against a
// database that already has tables it does nothing at all, whatever shape those
// tables are in. Without this check the mismatch surfaces much later as
// "converting NULL to string is unsupported" on whichever code path happens to
// read the wrong column first — a mystery hours after the deploy that caused it.
//
// The expectation comes from the structs themselves: a plain Go field means the
// column must be NOT NULL, and a sql.Null* field means it may be null. That is
// exactly the invariant a scan breaks on, so nothing has to be kept in step by
// hand.
func (s *Store) VerifySchema(ctx context.Context) error {
	var problems []string
	for _, table := range sortedKeys(rowStructs) {
		columns, err := liveColumns(ctx, s.db, table)
		if err != nil {
			return err
		}
		if len(columns) == 0 {
			problems = append(problems, fmt.Sprintf("table %q is missing", table))
			continue
		}
		for _, field := range expectedColumns(rowStructs[table]) {
			nullable, found := columns[field.name]
			switch {
			case !found:
				problems = append(problems, fmt.Sprintf("%s.%s is missing", table, field.name))
			case nullable && !field.nullable:
				problems = append(problems, fmt.Sprintf("%s.%s is nullable but must be NOT NULL", table, field.name))
			}
		}
	}
	if len(problems) == 0 {
		return nil
	}
	return fmt.Errorf("the database does not match this version's schema:\n  - %s\n\n"+
		"This build creates its tables with CREATE TABLE IF NOT EXISTS, so a database that "+
		"already has tables is left exactly as it was. Point it at an empty database, or drop "+
		"these tables and let it recreate them.", strings.Join(problems, "\n  - "))
}

type expectedColumn struct {
	name     string
	nullable bool
}

// expectedColumns reads a row struct's db tags, treating a sql.Null* field as
// the only kind that tolerates NULL.
func expectedColumns(row any) []expectedColumn {
	rowType := reflect.TypeOf(row)
	columns := make([]expectedColumn, 0, rowType.NumField())
	for index := range rowType.NumField() {
		field := rowType.Field(index)
		tag := strings.Split(field.Tag.Get("db"), ",")[0]
		if tag == "" {
			continue
		}
		columns = append(columns, expectedColumn{name: tag, nullable: toleratesNull(field.Type)})
	}
	return columns
}

// toleratesNull reports whether a field can scan a NULL. The sql.Null* types
// can; so can the domain's LocalDateTime, which carries its own Valid flag.
func toleratesNull(fieldType reflect.Type) bool {
	if fieldType.Kind() != reflect.Struct {
		return false
	}
	_, hasValid := fieldType.FieldByName("Valid")
	return hasValid
}

// liveColumns reads one table's columns as name -> nullable.
func liveColumns(ctx context.Context, db queryer, table string) (map[string]bool, error) {
	type columnRow struct {
		Name     string `db:"COLUMN_NAME"`
		Nullable string `db:"IS_NULLABLE"`
	}
	var rows []columnRow
	err := db.SelectContext(ctx, &rows,
		`SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?`, table)
	if err != nil {
		return nil, fmt.Errorf("read the schema of %s: %w", table, err)
	}
	columns := make(map[string]bool, len(rows))
	for _, row := range rows {
		columns[row.Name] = strings.EqualFold(row.Nullable, "YES")
	}
	return columns, nil
}

func sortedKeys(tables map[string]any) []string {
	names := make([]string, 0, len(tables))
	for name := range tables {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
