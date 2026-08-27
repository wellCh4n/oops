package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// AlertState is one row of application_alert_state (edge-trigger memory).
type AlertState struct {
	Firing       bool
	FiringSince  *time.Time
	LastNotified *time.Time
}

func (s *Store) FindAlertState(ctx context.Context, namespace, applicationName, environmentName, metric string) (*AlertState, error) {
	var state AlertState
	var firingSince, lastNotified sql.NullTime
	err := s.db.QueryRowContext(ctx,
		`SELECT firing, firing_since, last_notified_time FROM application_alert_state
		 WHERE namespace = ? AND application_name = ? AND environment_name = ? AND metric = ?`,
		namespace, applicationName, environmentName, metric).
		Scan(&state.Firing, &firingSince, &lastNotified)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if firingSince.Valid {
		state.FiringSince = &firingSince.Time
	}
	if lastNotified.Valid {
		state.LastNotified = &lastNotified.Time
	}
	return &state, nil
}

// SaveAlertState upserts the target's state, stamping firing_since on a fresh
// edge and last_notified_time on every save (a save only happens on notify).
func (s *Store) SaveAlertState(ctx context.Context, namespace, applicationName, environmentName, metric string, firing bool) error {
	now := Now()
	_, err := s.FindAlertState(ctx, namespace, applicationName, environmentName, metric)
	if errors.Is(err, ErrNotFound) {
		var firingSince any
		if firing {
			firingSince = now
		}
		_, err := s.db.ExecContext(ctx,
			`INSERT INTO application_alert_state
			 (id, created_time, namespace, application_name, environment_name, metric, firing, firing_since, last_notified_time)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			NewNanoID(), now, namespace, applicationName, environmentName, metric, firing, firingSince, now)
		return err
	}
	if err != nil {
		return err
	}
	if firing {
		_, err = s.db.ExecContext(ctx,
			`UPDATE application_alert_state
			 SET firing = 1, firing_since = COALESCE(firing_since, ?), last_notified_time = ?
			 WHERE namespace = ? AND application_name = ? AND environment_name = ? AND metric = ?`,
			now, now, namespace, applicationName, environmentName, metric)
		return err
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE application_alert_state
		 SET firing = 0, last_notified_time = ?
		 WHERE namespace = ? AND application_name = ? AND environment_name = ? AND metric = ?`,
		now, namespace, applicationName, environmentName, metric)
	return err
}
