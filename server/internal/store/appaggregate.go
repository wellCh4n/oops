package store

import (
	"context"

	"gorm.io/gorm"
)

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
	return s.orm.WithContext(ctx).Transaction(func(transaction *gorm.DB) error {
		for _, table := range aggregateTables {
			if err := transaction.Exec(
				"DELETE FROM "+table+" WHERE namespace = ? AND application_name = ?",
				namespace, name).Error; err != nil {
				return err
			}
		}
		return transaction.Exec(
			"DELETE FROM application WHERE namespace = ? AND name = ?", namespace, name).Error
	})
}

// MigrateApplicationNamespace mirrors migrateNamespace on both adapters:
// every aggregate table plus the pipelines move to the target namespace.
func (s *Store) MigrateApplicationNamespace(ctx context.Context, fromNamespace, toNamespace, name string) error {
	return s.orm.WithContext(ctx).Transaction(func(transaction *gorm.DB) error {
		for _, table := range append(aggregateTables, "pipeline") {
			if err := transaction.Exec(
				"UPDATE "+table+" SET namespace = ? WHERE namespace = ? AND application_name = ?",
				toNamespace, fromNamespace, name).Error; err != nil {
				return err
			}
		}
		return transaction.Exec(
			"UPDATE application SET namespace = ? WHERE namespace = ? AND name = ?",
			toNamespace, fromNamespace, name).Error
	})
}

// IsAdmin reports whether the user holds the ADMIN role.
func (s *Store) IsAdmin(ctx context.Context, userID string) bool {
	user, err := s.FindUserByID(ctx, userID)
	return err == nil && user.Role == "ADMIN"
}
