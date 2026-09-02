package scheduler

import (
	"context"
	"log/slog"
	"time"
)

// Job is one background task. Interval is how often Run is called; Run is
// expected to return rather than loop, and to tolerate being called again.
type Job interface {
	Name() string
	Interval() time.Duration
	Run(ctx context.Context)
}

// Start runs every job on its own goroutine until ctx is cancelled. A panic in
// one job is contained: a background task must never take the server down with
// it, and the next tick gets a clean attempt.
func Start(ctx context.Context, jobs ...Job) {
	for _, job := range jobs {
		go run(ctx, job)
	}
}

func run(ctx context.Context, job Job) {
	ticker := time.NewTicker(job.Interval())
	defer ticker.Stop()
	slog.Info("scheduler started", "job", job.Name(), "interval", job.Interval())
	for {
		select {
		case <-ctx.Done():
			slog.Info("scheduler stopped", "job", job.Name())
			return
		case <-ticker.C:
			runOnce(ctx, job)
		}
	}
}

func runOnce(ctx context.Context, job Job) {
	defer func() {
		if recovered := recover(); recovered != nil {
			slog.Error("scheduler job panicked", "job", job.Name(), "panic", recovered)
		}
	}()
	job.Run(ctx)
}

// minuteAligned is the tick for jobs that match a cron expression: they must
// fire once per minute, on the minute, or an expression could be missed.
const minuteAligned = time.Minute
