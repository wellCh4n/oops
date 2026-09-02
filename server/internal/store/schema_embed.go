package store

import _ "embed"

// schemaSQL is the schema in one readable place. Nothing reads it at runtime:
// the database is built by the migration, and it is embedded only so the tests
// can check the migration and the row structs against it.
//
//go:embed schema.sql
var schemaSQL string
