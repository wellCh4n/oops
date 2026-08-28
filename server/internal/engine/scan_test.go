package engine

import (
	"strings"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// Mirrors PipelineHealthVerificationTests + PipelineVerificationScanTests.

func statefulSetWith(desired, updated, ready int32, generation, observed int64, startedAt string) *appsv1.StatefulSet {
	statefulSet := &appsv1.StatefulSet{
		ObjectMeta: metav1.ObjectMeta{Generation: generation},
		Spec:       appsv1.StatefulSetSpec{Replicas: &desired},
		Status: appsv1.StatefulSetStatus{
			ObservedGeneration: observed,
			UpdatedReplicas:    updated,
			ReadyReplicas:      ready,
		},
	}
	if startedAt != "" {
		statefulSet.Annotations = map[string]string{rolloutStartedAtAnnotation: startedAt}
	}
	return statefulSet
}

func podWaiting(name, reason string) corev1.Pod {
	return corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: name},
		Status: corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{
			State: corev1.ContainerState{Waiting: &corev1.ContainerStateWaiting{Reason: reason}},
		}}},
	}
}

func TestEvaluateRolloutComplete(t *testing.T) {
	verdict := evaluateRollout(statefulSetWith(2, 2, 2, 5, 5, ""), nil, time.Now())
	if !verdict.complete || verdict.failureMessage != "" {
		t.Errorf("expected complete, got %+v", verdict)
	}
}

func TestEvaluateRolloutWaitsWhileConverging(t *testing.T) {
	recent := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339)
	verdict := evaluateRollout(statefulSetWith(2, 1, 1, 5, 5, recent), nil, time.Now())
	if verdict.complete || verdict.failureMessage != "" {
		t.Errorf("expected still-in-progress, got %+v", verdict)
	}
}

func TestEvaluateRolloutIgnoresStaleObservation(t *testing.T) {
	// Status counts look complete but reflect the previous generation.
	verdict := evaluateRollout(statefulSetWith(2, 2, 2, 6, 5, ""), nil, time.Now())
	if verdict.complete {
		t.Errorf("stale observedGeneration must not complete the rollout: %+v", verdict)
	}
}

func TestEvaluateRolloutFailsOnFatalWaitingReason(t *testing.T) {
	for _, reason := range []string{"ImagePullBackOff", "ErrImagePull", "CrashLoopBackOff"} {
		verdict := evaluateRollout(statefulSetWith(1, 1, 0, 5, 5, ""), []corev1.Pod{podWaiting("app-0", reason)}, time.Now())
		if verdict.complete || !strings.Contains(verdict.failureMessage, reason) || !strings.Contains(verdict.failureMessage, "app-0") {
			t.Errorf("reason %s: got %+v", reason, verdict)
		}
	}
}

func TestEvaluateRolloutTolratesBenignWaitingReason(t *testing.T) {
	recent := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339)
	verdict := evaluateRollout(statefulSetWith(1, 1, 0, 5, 5, recent), []corev1.Pod{podWaiting("app-0", "ContainerCreating")}, time.Now())
	if verdict.complete || verdict.failureMessage != "" {
		t.Errorf("ContainerCreating should keep waiting, got %+v", verdict)
	}
}

func TestEvaluateRolloutTimesOut(t *testing.T) {
	stale := time.Now().Add(-rolloutTimeout - time.Minute).UTC().Format(time.RFC3339)
	verdict := evaluateRollout(statefulSetWith(1, 1, 0, 5, 5, stale), nil, time.Now())
	if verdict.failureMessage == "" || !strings.Contains(verdict.failureMessage, "超时") {
		t.Errorf("expected timeout failure, got %+v", verdict)
	}
}

func TestEvaluateRolloutNoAnnotationNeverTimesOut(t *testing.T) {
	verdict := evaluateRollout(statefulSetWith(1, 1, 0, 5, 5, ""), nil, time.Now())
	if verdict.complete || verdict.failureMessage != "" {
		t.Errorf("without started-at annotation the rollout must keep waiting: %+v", verdict)
	}
}

// A fatal pod beats a complete-looking StatefulSet: the failure is what the
// operator must see.
func TestEvaluateRolloutFailureWinsOverComplete(t *testing.T) {
	verdict := evaluateRollout(statefulSetWith(1, 1, 1, 5, 5, ""), []corev1.Pod{podWaiting("app-0", "CrashLoopBackOff")}, time.Now())
	if verdict.complete || verdict.failureMessage == "" {
		t.Errorf("expected failure, got %+v", verdict)
	}
}
