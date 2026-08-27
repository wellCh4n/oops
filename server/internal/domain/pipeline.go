// Package domain holds the pure business rules — status machines, naming and
// validation policies, identity — with no persistence, HTTP or Kubernetes
// dependencies. The Go counterpart of the Java domain/ layer, collapsed to the
// rules that actually carry behaviour.
package domain

import "fmt"

// Pipeline statuses, mirroring PipelineStatus.
const (
	PipelineInitialized    = "INITIALIZED"
	PipelineRunning        = "RUNNING"
	PipelineBuildSucceeded = "BUILD_SUCCEEDED"
	PipelineDeploying      = "DEPLOYING"
	PipelineRollingOut     = "ROLLING_OUT"
	PipelineSucceeded      = "SUCCEEDED"
	PipelineError          = "ERROR"
	PipelineStopped        = "STOPPED"
)

// allowedPipelineTransitions mirrors PipelineStateMachine.
var allowedPipelineTransitions = map[string]map[string]bool{
	PipelineInitialized:    {PipelineRunning: true, PipelineDeploying: true, PipelineError: true, PipelineStopped: true},
	PipelineRunning:        {PipelineBuildSucceeded: true, PipelineDeploying: true, PipelineError: true, PipelineStopped: true},
	PipelineBuildSucceeded: {PipelineDeploying: true, PipelineStopped: true},
	PipelineDeploying:      {PipelineRollingOut: true, PipelineError: true, PipelineStopped: true},
	PipelineRollingOut:     {PipelineSucceeded: true, PipelineError: true},
	PipelineStopped:        {},
	PipelineSucceeded:      {},
	PipelineError:          {},
}

func EnsurePipelineTransition(current, target string) error {
	if !allowedPipelineTransitions[current][target] {
		return fmt.Errorf("illegal pipeline status transition: %s -> %s", current, target)
	}
	return nil
}

func IsPipelineTerminal(status string) bool {
	return status == PipelineSucceeded || status == PipelineError || status == PipelineStopped
}

// ActivePipelineStatuses backs the duplicate-deploy guard
// (DeploymentConcurrencyPolicy).
var ActivePipelineStatuses = []string{PipelineRunning, PipelineDeploying, PipelineRollingOut}
