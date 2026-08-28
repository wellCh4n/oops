package store

import (
	"context"
	"errors"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"gorm.io/gorm"
)

type alertStateRecord struct {
	ID              string
	CreatedTime     *LocalDateTime
	Namespace       string
	ApplicationName string
	EnvironmentName string
	Metric          string
	Firing          bool
	FiringSince     *time.Time
	LastNotified    *time.Time `gorm:"column:last_notified_time"`
}

func (alertStateRecord) TableName() string { return "application_alert_state" }

// AlertState is one row of application_alert_state (edge-trigger memory).
type AlertState struct {
	Firing       bool
	FiringSince  *time.Time
	LastNotified *time.Time
}

func (s *Store) FindAlertState(ctx context.Context, namespace, applicationName, environmentName, metric string) (*AlertState, error) {
	var record alertStateRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ? AND environment_name = ? AND metric = ?",
			namespace, applicationName, environmentName, metric).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	return &AlertState{
		Firing:       record.Firing,
		FiringSince:  record.FiringSince,
		LastNotified: record.LastNotified,
	}, nil
}

// SaveAlertState upserts the target's state, stamping firing_since on a fresh
// edge and last_notified_time on every save (a save only happens on notify).
func (s *Store) SaveAlertState(ctx context.Context, namespace, applicationName, environmentName, metric string, firing bool) error {
	now := time.Now().UTC()
	_, err := s.FindAlertState(ctx, namespace, applicationName, environmentName, metric)
	if errors.Is(err, ErrNotFound) {
		record := alertStateRecord{
			ID: domain.NewID(), CreatedTime: Now(),
			Namespace: namespace, ApplicationName: applicationName,
			EnvironmentName: environmentName, Metric: metric,
			Firing: firing, LastNotified: &now,
		}
		if firing {
			record.FiringSince = &now
		}
		return s.orm.WithContext(ctx).Create(&record).Error
	}
	if err != nil {
		return err
	}
	target := s.orm.WithContext(ctx).Model(&alertStateRecord{}).
		Where("namespace = ? AND application_name = ? AND environment_name = ? AND metric = ?",
			namespace, applicationName, environmentName, metric)
	if firing {
		return target.Updates(map[string]any{
			"firing":             true,
			"firing_since":       gorm.Expr("COALESCE(firing_since, ?)", now),
			"last_notified_time": now,
		}).Error
	}
	return target.Updates(map[string]any{
		"firing":             false,
		"last_notified_time": now,
	}).Error
}
