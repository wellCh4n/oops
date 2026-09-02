package store

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store/sqltypes"
)

// AlertStateRepository holds one row per application + environment + metric,
// which is what makes the resource-alert scan edge-triggered rather than a
// message every minute.
type AlertStateRepository struct {
	store *Store
}

func alertStateFromRow(row alertStateRow) domain.AlertState {
	return domain.AlertState{
		ID:               row.ID,
		CreatedTime:      row.CreatedTime,
		Namespace:        row.Namespace,
		ApplicationName:  row.ApplicationName,
		Environment:      row.Environment,
		Metric:           domain.AlertMetric(row.Metric),
		Firing:           bool(row.Firing),
		FiringSince:      row.FiringSince,
		LastNotifiedTime: row.LastNotifiedTime,
	}
}

// FindAll reads every alert state in one query — the scan job wants the whole
// table anyway, and one read beats one per application.
func (r *AlertStateRepository) FindAll(ctx context.Context) ([]domain.AlertState, error) {
	rows, err := list[alertStateRow](ctx, r.store.db, `SELECT * FROM application_alert_state`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.AlertState, 0, len(rows))
	for _, row := range rows {
		result = append(result, alertStateFromRow(row))
	}
	return result, nil
}

// Find loads one alert state; nil when the metric has never fired.
func (r *AlertStateRepository) Find(ctx context.Context, namespace, applicationName, environment string, metric domain.AlertMetric) (*domain.AlertState, error) {
	row, err := getOrNil[alertStateRow](ctx, r.store.db,
		`SELECT * FROM application_alert_state
WHERE namespace = ? AND application_name = ? AND environment = ? AND metric = ? LIMIT 1`,
		namespace, applicationName, environment, string(metric))
	if err != nil || row == nil {
		return nil, err
	}
	state := alertStateFromRow(*row)
	return &state, nil
}

// Save writes an alert state, keyed on the natural key rather than the id, so
// two scans racing on the same metric cannot create two rows for it.
func (r *AlertStateRepository) Save(ctx context.Context, state *domain.AlertState) (*domain.AlertState, error) {
	state.ID = ensureID(state.ID)
	if state.CreatedTime.IsZero() {
		state.CreatedTime = domain.Now()
	}
	if err := exec(ctx, r.store.db,
		`INSERT INTO application_alert_state
(id, created_time, namespace, application_name, environment, metric, firing, firing_since, last_notified_time)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
  firing = VALUES(firing), firing_since = VALUES(firing_since), last_notified_time = VALUES(last_notified_time)`,
		state.ID, state.CreatedTime, state.Namespace, state.ApplicationName, state.Environment,
		string(state.Metric), sqltypes.BitBool(state.Firing), state.FiringSince, state.LastNotifiedTime); err != nil {
		return nil, err
	}
	return state, nil
}

// DeleteByApplication drops an application's alert states, on delete.
func (r *AlertStateRepository) DeleteByApplication(ctx context.Context, namespace, applicationName string) error {
	_, err := execRows(ctx, r.store.db,
		`DELETE FROM application_alert_state WHERE namespace = ? AND application_name = ?`,
		namespace, applicationName)
	return err
}

// MigrateNamespace moves an application's alert states to another namespace.
func (r *AlertStateRepository) MigrateNamespace(ctx context.Context, sourceNamespace, targetNamespace, applicationName string) (int64, error) {
	return execRows(ctx, r.store.db,
		`UPDATE application_alert_state SET namespace = ? WHERE namespace = ? AND application_name = ?`,
		targetNamespace, sourceNamespace, applicationName)
}
