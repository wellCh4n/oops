package scheduler

import (
	"context"
	"log/slog"
	"time"

	"github.com/wellch4n/oops/server/internal/cron"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/service"
)

// ScheduledRestart rolls applications whose expert config asks for it. It runs
// once a minute and matches the minute, so an expression can fire at most once
// per tick however long the scan takes.
type ScheduledRestart struct {
	services *service.Services
}

func NewScheduledRestart(services *service.Services) *ScheduledRestart {
	return &ScheduledRestart{services: services}
}

func (j *ScheduledRestart) Name() string            { return "scheduled-restart" }
func (j *ScheduledRestart) Interval() time.Duration { return minuteAligned }

func (j *ScheduledRestart) Run(ctx context.Context) {
	// The minute is captured once so every expression is matched against the
	// same instant, however long the scan takes.
	now := time.Now()
	configs, err := j.services.Store.Applications().FindAllExpertConfigs(ctx)
	if err != nil {
		slog.Error("could not list expert configs", "error", err)
		return
	}
	for _, config := range configs {
		for _, environmentConfig := range config.EnvironmentConfigs {
			if !environmentConfig.ScheduledRestartEnabled {
				continue
			}
			expression := domain.Deref(environmentConfig.ScheduledRestartCron)
			if !cron.MatchesMinute(expression, now) {
				continue
			}
			j.restart(ctx, config.Namespace, config.ApplicationName, domain.Deref(environmentConfig.Environment))
		}
	}
}

func (j *ScheduledRestart) restart(ctx context.Context, namespace, applicationName, environmentName string) {
	environment, err := j.services.Environments.FindByName(ctx, environmentName)
	if err != nil || environment == nil {
		slog.Warn("skipping a scheduled restart for an unknown environment",
			"namespace", namespace, "application", applicationName, "environment", environmentName)
		return
	}
	if err := j.services.Runtime.RolloutRestart(ctx, environment, namespace, applicationName); err != nil {
		slog.Error("scheduled restart failed",
			"namespace", namespace, "application", applicationName, "environment", environmentName, "error", err)
		return
	}
	slog.Info("scheduled restart triggered",
		"namespace", namespace, "application", applicationName, "environment", environmentName)
}
