package k8s

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	eventsv1 "k8s.io/api/events/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/watch"
	"k8s.io/client-go/kubernetes"
)

// Label scheme shared with the Java gateways.
const (
	applicationTypeLabel = "oops.type"
	applicationNameLabel = "oops.app.name"
	applicationTypeValue = "APPLICATION"
)

func applicationSelector(applicationName string) string {
	return fmt.Sprintf("%s=%s,%s=%s",
		applicationTypeLabel, applicationTypeValue, applicationNameLabel, applicationName)
}

// ContainerStatusView / PodStatusView mirror ApplicationPodStatusView.
type ContainerStatusView struct {
	Name         *string `json:"name"`
	Image        *string `json:"image"`
	Ready        *bool   `json:"ready"`
	RestartCount *int32  `json:"restartCount"`
	StartedAt    *string `json:"startedAt"`
	Reason       *string `json:"reason"`
}

type PodStatusView struct {
	Name       string                `json:"name"`
	Namespace  string                `json:"namespace"`
	Status     *string               `json:"status"`
	PodIP      *string               `json:"podIP"`
	NodeName   *string               `json:"nodeName"`
	Containers []ContainerStatusView `json:"containers"`
}

func toPodStatusView(pod *corev1.Pod) PodStatusView {
	view := PodStatusView{
		Name:       pod.Name,
		Namespace:  pod.Namespace,
		Containers: []ContainerStatusView{},
	}
	if phase := string(pod.Status.Phase); phase != "" {
		view.Status = &phase
	}
	if pod.Status.PodIP != "" {
		podIP := pod.Status.PodIP
		view.PodIP = &podIP
	}
	if pod.Spec.NodeName != "" {
		nodeName := pod.Spec.NodeName
		view.NodeName = &nodeName
	}
	for _, containerStatus := range pod.Status.ContainerStatuses {
		name, image := containerStatus.Name, containerStatus.Image
		ready, restartCount := containerStatus.Ready, containerStatus.RestartCount
		container := ContainerStatusView{
			Name: &name, Image: &image, Ready: &ready, RestartCount: &restartCount,
		}
		if running := containerStatus.State.Running; running != nil {
			startedAt := running.StartedAt.UTC().Format(time.RFC3339)
			container.StartedAt = &startedAt
		}
		if waiting := containerStatus.State.Waiting; waiting != nil && waiting.Reason != "" {
			reason := waiting.Reason
			container.Reason = &reason
		} else if terminated := containerStatus.State.Terminated; terminated != nil && terminated.Reason != "" {
			reason := terminated.Reason
			container.Reason = &reason
		}
		view.Containers = append(view.Containers, container)
	}
	return view
}

func ListPodStatuses(ctx context.Context, client *kubernetes.Clientset, namespace, applicationName string) ([]PodStatusView, error) {
	pods, err := client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationSelector(applicationName),
	})
	if err != nil {
		return nil, err
	}
	views := []PodStatusView{}
	for i := range pods.Items {
		views = append(views, toPodStatusView(&pods.Items[i]))
	}
	return views, nil
}

// EventView mirrors ApplicationEventView; time is an RFC3339 instant.
type EventView struct {
	Time         *time.Time `json:"time"`
	Type         *string    `json:"type"`
	ResourceKind *string    `json:"resourceKind"`
	ResourceName *string    `json:"resourceName"`
	Reason       *string    `json:"reason"`
	Message      *string    `json:"message"`
	Count        int32      `json:"count"`
}

func isApplicationObjectReference(reference *corev1.ObjectReference, applicationName string, podNames map[string]struct{}) bool {
	if reference == nil || reference.Kind == "" || reference.Name == "" {
		return false
	}
	switch reference.Kind {
	case "Pod":
		_, known := podNames[reference.Name]
		return known || strings.HasPrefix(reference.Name, applicationName+"-")
	case "StatefulSet", "Service", "ConfigMap":
		return reference.Name == applicationName
	case "IngressRoute":
		return reference.Name == applicationName || strings.HasPrefix(reference.Name, applicationName+"-")
	}
	return false
}

func eventInstant(event *eventsv1.Event) *time.Time {
	if event.Series != nil {
		t := event.Series.LastObservedTime.Time
		if !t.IsZero() {
			return &t
		}
	}
	if !event.EventTime.IsZero() {
		t := event.EventTime.Time
		return &t
	}
	if !event.DeprecatedLastTimestamp.IsZero() {
		t := event.DeprecatedLastTimestamp.Time
		return &t
	}
	if !event.CreationTimestamp.IsZero() {
		t := event.CreationTimestamp.Time
		return &t
	}
	return nil
}

func eventCount(event *eventsv1.Event) int32 {
	if event.Series != nil && event.Series.Count != 0 {
		return event.Series.Count
	}
	if event.DeprecatedCount != 0 {
		return event.DeprecatedCount
	}
	return 1
}

func ListApplicationEvents(ctx context.Context, client *kubernetes.Clientset, namespace, applicationName string, since *time.Time, limit int) ([]EventView, error) {
	pods, err := client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationSelector(applicationName),
	})
	if err != nil {
		return nil, err
	}
	podNames := map[string]struct{}{}
	for _, pod := range pods.Items {
		podNames[pod.Name] = struct{}{}
	}

	events, err := client.EventsV1().Events(namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, err
	}
	views := []EventView{}
	for i := range events.Items {
		event := &events.Items[i]
		if !isApplicationObjectReference(&event.Regarding, applicationName, podNames) &&
			!isApplicationObjectReference(event.Related, applicationName, podNames) {
			continue
		}
		instant := eventInstant(event)
		if instant == nil || (since != nil && instant.Before(*since)) {
			continue
		}
		eventType, reason, note := event.Type, event.Reason, event.Note
		kind, name := event.Regarding.Kind, event.Regarding.Name
		views = append(views, EventView{
			Time:         instant,
			Type:         &eventType,
			ResourceKind: &kind,
			ResourceName: &name,
			Reason:       &reason,
			Message:      &note,
			Count:        eventCount(event),
		})
	}
	sort.Slice(views, func(i, j int) bool { return views[i].Time.After(*views[j].Time) })
	if limit > 0 && len(views) > limit {
		views = views[:limit]
	}
	return views, nil
}

// FindInternalServiceDomain matches findInternalServiceDomain:
// "{service}.{namespace}.svc.cluster.local" for the first labelled Service.
func FindInternalServiceDomain(ctx context.Context, client *kubernetes.Clientset, namespace, applicationName string) (*string, error) {
	services, err := client.CoreV1().Services(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationNameLabel + "=" + applicationName,
	})
	if err != nil {
		return nil, err
	}
	if len(services.Items) == 0 {
		return nil, nil
	}
	domain := fmt.Sprintf("%s.%s.svc.cluster.local", services.Items[0].Name, services.Items[0].Namespace)
	return &domain, nil
}

func RestartPod(ctx context.Context, client *kubernetes.Clientset, namespace, podName string) error {
	return client.CoreV1().Pods(namespace).Delete(ctx, podName, metav1.DeleteOptions{})
}

// WatchPodStatuses streams the full pod-status snapshot on every change; the
// emit callback receives the current state, matching the Java SSE "status"
// events. It blocks until ctx is cancelled or the watch closes.
func WatchPodStatuses(ctx context.Context, client *kubernetes.Clientset, namespace, applicationName string, emit func([]PodStatusView) error) error {
	options := metav1.ListOptions{LabelSelector: applicationSelector(applicationName)}
	initial, err := client.CoreV1().Pods(namespace).List(ctx, options)
	if err != nil {
		return err
	}
	state := map[string]PodStatusView{}
	for i := range initial.Items {
		state[initial.Items[i].Name] = toPodStatusView(&initial.Items[i])
	}
	snapshot := func() []PodStatusView {
		views := make([]PodStatusView, 0, len(state))
		for _, view := range state {
			views = append(views, view)
		}
		sort.Slice(views, func(i, j int) bool { return views[i].Name < views[j].Name })
		return views
	}
	if err := emit(snapshot()); err != nil {
		return nil // receiver disconnected
	}

	options.ResourceVersion = initial.ResourceVersion
	watcher, err := client.CoreV1().Pods(namespace).Watch(ctx, options)
	if err != nil {
		return err
	}
	defer watcher.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case event, open := <-watcher.ResultChan():
			if !open {
				return nil
			}
			pod, isPod := event.Object.(*corev1.Pod)
			if !isPod {
				continue
			}
			switch event.Type {
			case watch.Added, watch.Modified:
				state[pod.Name] = toPodStatusView(pod)
			case watch.Deleted:
				delete(state, pod.Name)
			default:
				continue
			}
			if err := emit(snapshot()); err != nil {
				return nil
			}
		}
	}
}
