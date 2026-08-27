package store

import "context"

var aggregateTables = []string{
	"application_environment",
	"application_build_config",
	"application_runtime_spec",
	"application_service_config",
	"application_expert_config",
	"application_collaborator",
	"application_alert_state",
}

// DeleteApplicationAggregate mirrors ApplicationPersistenceAdapter.deleteAggregate.
func (s *Store) DeleteApplicationAggregate(ctx context.Context, namespace, name string) error {
	transaction, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	for _, table := range aggregateTables {
		if _, err := transaction.ExecContext(ctx,
			"DELETE FROM "+table+" WHERE namespace = ? AND application_name = ?", namespace, name); err != nil {
			return err
		}
	}
	if _, err := transaction.ExecContext(ctx,
		"DELETE FROM application WHERE namespace = ? AND name = ?", namespace, name); err != nil {
		return err
	}
	return transaction.Commit()
}

// MigrateApplicationNamespace mirrors migrateNamespace on both adapters:
// every aggregate table plus the pipelines move to the target namespace.
func (s *Store) MigrateApplicationNamespace(ctx context.Context, fromNamespace, toNamespace, name string) error {
	transaction, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	for _, table := range append(aggregateTables, "pipeline") {
		if _, err := transaction.ExecContext(ctx,
			"UPDATE "+table+" SET namespace = ? WHERE namespace = ? AND application_name = ?",
			toNamespace, fromNamespace, name); err != nil {
			return err
		}
	}
	if _, err := transaction.ExecContext(ctx,
		"UPDATE application SET namespace = ? WHERE namespace = ? AND name = ?",
		toNamespace, fromNamespace, name); err != nil {
		return err
	}
	return transaction.Commit()
}

// NamespaceExists checks the namespace registry (not the cluster).
func (s *Store) NamespaceRecordExists(ctx context.Context, name string) (bool, error) {
	var count int
	err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM namespace WHERE name = ?", name).Scan(&count)
	return count > 0, err
}

// IsAdmin reports whether the user holds the ADMIN role.
func (s *Store) IsAdmin(ctx context.Context, userID string) bool {
	user, err := s.FindUserByID(ctx, userID)
	return err == nil && user.Role == "ADMIN"
}
