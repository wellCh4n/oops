package store

import (
	"reflect"
	"regexp"
	"sort"
	"strings"
	"testing"
)

var (
	createTablePattern = regexp.MustCompile("CREATE TABLE (?:IF NOT EXISTS )?`([a-z_]+)`")
	columnPattern      = regexp.MustCompile("(?m)^\\s+`(\\w+)` ")
)

// The migration is the only description of the schema there is, so it is what
// the structs are checked against — reading the same file the database is built
// from, rather than a second copy that could itself drift.
func TestEveryRowStructMatchesItsTable(t *testing.T) {
	tables := tableColumns(t, migrationSQL(t))
	if len(tables) != len(rowStructs) {
		t.Fatalf("the migration creates %d tables but %d row structs are declared", len(tables), len(rowStructs))
	}
	for table, row := range rowStructs {
		columns, found := tables[table]
		if !found {
			t.Errorf("the migration creates no table %q", table)
			continue
		}
		if fields := dbTags(row); !equal(fields, columns) {
			t.Errorf("%s: struct scans %v, table has %v", table, fields, columns)
		}
	}
}

// migrationSQL returns the single baseline migration.
func migrationSQL(t *testing.T) string {
	t.Helper()
	entries, err := migrationFiles.ReadDir("migrations")
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected a single baseline migration, found %d", len(entries))
	}
	content, err := migrationFiles.ReadFile("migrations/" + entries[0].Name())
	if err != nil {
		t.Fatal(err)
	}
	return string(content)
}

// tableColumns reads every CREATE TABLE into table -> sorted column names.
func tableColumns(t *testing.T, schema string) map[string][]string {
	t.Helper()
	tables := map[string][]string{}
	blocks := createTablePattern.FindAllStringSubmatchIndex(schema, -1)
	for index, block := range blocks {
		name := schema[block[2]:block[3]]
		end := len(schema)
		if index+1 < len(blocks) {
			end = blocks[index+1][0]
		}
		var columns []string
		for _, match := range columnPattern.FindAllStringSubmatch(schema[block[1]:end], -1) {
			columns = append(columns, match[1])
		}
		sort.Strings(columns)
		tables[name] = columns
	}
	return tables
}

func dbTags(row any) []string {
	rowType := reflect.TypeOf(row)
	tags := make([]string, 0, rowType.NumField())
	for index := range rowType.NumField() {
		if tag := rowType.Field(index).Tag.Get("db"); tag != "" {
			tags = append(tags, strings.Split(tag, ",")[0])
		}
	}
	sort.Strings(tags)
	return tags
}

func equal(left, right []string) bool {
	if len(left) != len(right) || len(left) == 0 {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
