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

// rowStructs maps each row struct to the table it is scanned from. Reads use
// `SELECT *`, so a struct that has drifted from its table breaks every query
// against it — which used to be impossible when the structs were generated and
// is now only impossible because of this test.
var rowStructs = map[string]any{
	"application":                applicationRow{},
	"application_build_config":   buildConfigRow{},
	"application_environment":    applicationEnvironmentRow{},
	"application_runtime_spec":   runtimeSpecRow{},
	"application_service_config": serviceConfigRow{},
	"application_expert_config":  expertConfigRow{},
	"application_collaborator":   collaboratorRow{},
	"application_alert_state":    alertStateRow{},
	"domain":                     domainRow{},
	"environment":                environmentRow{},
	"external_account":           externalAccountRow{},
	"namespace":                  namespaceRow{},
	"pipeline":                   pipelineRow{},
	"user":                       userRow{},
}

func TestEveryRowStructMatchesItsTable(t *testing.T) {
	tables := tableColumns(schemaSQL)
	if len(tables) != len(rowStructs) {
		t.Fatalf("schema has %d tables but %d row structs are declared", len(tables), len(rowStructs))
	}
	for table, row := range rowStructs {
		columns, found := tables[table]
		if !found {
			t.Errorf("no table %q in the schema", table)
			continue
		}
		fields := dbTags(row)
		if !equal(fields, columns) {
			t.Errorf("%s: struct scans %v, table has %v", table, fields, columns)
		}
	}
}

// The migration and the schema file are two copies of one schema, so the thing
// worth testing is that they have not drifted apart.
func TestTheOneMigrationCreatesEveryTable(t *testing.T) {
	entries, err := migrationFiles.ReadDir("migrations")
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected a single baseline migration, found %d", len(entries))
	}
	migration, err := migrationFiles.ReadFile("migrations/" + entries[0].Name())
	if err != nil {
		t.Fatal(err)
	}
	got, want := tableNames(string(migration)), tableNames(schemaSQL)
	if !equal(got, want) {
		t.Fatalf("migration creates %v, schema declares %v", got, want)
	}
}

// tableColumns reads every CREATE TABLE into table -> sorted column names.
func tableColumns(schema string) map[string][]string {
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

func tableNames(schema string) []string {
	var names []string
	for _, match := range createTablePattern.FindAllStringSubmatch(schema, -1) {
		names = append(names, match[1])
	}
	sort.Strings(names)
	return names
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
