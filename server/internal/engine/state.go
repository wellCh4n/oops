// Package engine is the pipeline execution core: build Job creation, the
// deploy processor chain, and the scan loops. It is the Go counterpart of
// infrastructure/kubernetes/task + infrastructure/scheduler.
package engine

import (
	"fmt"

	"github.com/wellch4n/oops/server/internal/store"
)

var allowedTransitions = map[string]map[string]bool{
	store.StatusInitialized:    {store.StatusRunning: true, store.StatusDeploying: true, store.StatusError: true, store.StatusStopped: true},
	store.StatusRunning:        {store.StatusBuildSucceeded: true, store.StatusDeploying: true, store.StatusError: true, store.StatusStopped: true},
	store.StatusBuildSucceeded: {store.StatusDeploying: true, store.StatusStopped: true},
	store.StatusDeploying:      {store.StatusRollingOut: true, store.StatusError: true, store.StatusStopped: true},
	store.StatusRollingOut:     {store.StatusSucceeded: true, store.StatusError: true},
	store.StatusStopped:        {},
	store.StatusSucceeded:      {},
	store.StatusError:          {},
}

func ensureCanTransition(current, target string) error {
	if !allowedTransitions[current][target] {
		return fmt.Errorf("illegal pipeline status transition: %s -> %s", current, target)
	}
	return nil
}

func isTerminal(status string) bool {
	return status == store.StatusSucceeded || status == store.StatusError || status == store.StatusStopped
}
