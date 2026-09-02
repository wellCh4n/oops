package k8s

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	appsv1 "k8s.io/api/apps/v1"
	autoscalingv1 "k8s.io/api/autoscaling/v1"
	corev1 "k8s.io/api/core/v1"
	eventsv1 "k8s.io/api/events/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/watch"
)

// RuntimeGateway is the Go counterpart of KubernetesApplicationRuntimeGateway.
type RuntimeGateway struct{ pool *Pool }

func NewRuntimeGateway(pool *Pool) *RuntimeGateway { return &RuntimeGateway{pool: pool} }

// DeploymentHealth mirrors the Java record of the same name.
type DeploymentHealth struct {
	WorkloadMissing bool
	RolloutComplete bool
	DesiredReplicas *int32
	ReadyReplicas   *int32
	FailureReason   string
	NotReadySince   *time.Time
}

// HasFailure reports a fatal container waiting reason.
func (h DeploymentHealth) HasFailure() bool { return !isBlank(h.FailureReason) }

// NotReadyLongerThan reports notReadySince + timeout <= now.
func (h DeploymentHealth) NotReadyLongerThan(now time.Time, timeout time.Duration) bool {
	return h.NotReadySince != nil && !h.NotReadySince.Add(timeout).After(now)
}

var fatalWaitingReasons = map[string]bool{"ImagePullBackOff": true, "ErrImagePull": true, "CrashLoopBackOff": true}

// GetDeploymentHealth mirrors spec-deploy §2.
func (g *RuntimeGateway) GetDeploymentHealth(ctx context.Context, environment *domain.Environment, namespace, applicationName string) (DeploymentHealth, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return DeploymentHealth{}, err
	}
	health, err := getDeploymentHealth(ctx, client, namespace, applicationName)
	return health, TranslateError(err)
}

func getDeploymentHealth(ctx context.Context, client *Client, namespace, applicationName string) (DeploymentHealth, error) {
	statefulSet, err := client.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return DeploymentHealth{WorkloadMissing: true}, nil
		}
		return DeploymentHealth{}, err
	}
	desired := int32(0)
	if statefulSet.Spec.Replicas != nil {
		desired = *statefulSet.Spec.Replicas
	}
	ready := statefulSet.Status.ReadyReplicas
	updated := statefulSet.Status.UpdatedReplicas
	generationObserved := statefulSet.Generation == 0 || statefulSet.Status.ObservedGeneration >= statefulSet.Generation
	rolloutComplete := generationObserved && updated == desired && ready == desired

	pods, err := listApplicationPods(ctx, client, namespace, applicationName)
	if err != nil {
		return DeploymentHealth{}, err
	}
	failureReason := ""
	if generationObserved {
		failureReason = FindFatalPodWaitingReason(pods, statefulSet.Status.UpdateRevision)
	}
	var notReadySince *time.Time
	if !rolloutComplete {
		notReadySince = FindRolloutNotReadySince(statefulSet, pods)
	}
	health := DeploymentHealth{
		RolloutComplete: rolloutComplete,
		DesiredReplicas: statefulSet.Spec.Replicas,
		FailureReason:   failureReason,
		NotReadySince:   notReadySince,
	}
	// status.readyReplicas is a plain int32 in Go; Java exposed the raw nullable Integer.
	health.ReadyReplicas = domain.Ptr(ready)
	return health, nil
}

// FindFatalPodWaitingReason returns "<reason> (<pod>)" for the first pod on
// the update revision whose container waits with a fatal reason.
func FindFatalPodWaitingReason(pods []corev1.Pod, updateRevision string) string {
	for index := range pods {
		pod := &pods[index]
		if podIsTerminating(pod) {
			continue
		}
		if !isBlank(updateRevision) && !podIsAtRevision(pod, updateRevision) {
			continue
		}
		if len(pod.Status.ContainerStatuses) == 0 {
			continue
		}
		for _, containerStatus := range pod.Status.ContainerStatuses {
			if containerStatus.State.Waiting != nil && fatalWaitingReasons[containerStatus.State.Waiting.Reason] {
				return containerStatus.State.Waiting.Reason + " (" + pod.Name + ")"
			}
		}
	}
	return ""
}

// FindRolloutNotReadySince prefers the oops.rollout.started-at annotation and
// falls back to the earliest not-ready instant across the pods.
func FindRolloutNotReadySince(statefulSet *appsv1.StatefulSet, pods []corev1.Pod) *time.Time {
	if started := parseInstant(statefulSet.Annotations[RolloutStartedAtAnnotation]); started != nil {
		return started
	}
	var earliest *time.Time
	for index := range pods {
		since := podNotReadySince(&pods[index])
		if since != nil && (earliest == nil || since.Before(*earliest)) {
			earliest = since
		}
	}
	return earliest
}

func podNotReadySince(pod *corev1.Pod) *time.Time {
	created := creationTime(pod.CreationTimestamp)
	if len(pod.Status.Conditions) == 0 {
		return created
	}
	for _, condition := range pod.Status.Conditions {
		if condition.Type != corev1.PodReady {
			continue
		}
		if strings.EqualFold(string(condition.Status), "True") {
			return nil
		}
		if !condition.LastTransitionTime.IsZero() {
			transition := condition.LastTransitionTime.Time
			return &transition
		}
		return created
	}
	return nil
}

func creationTime(timestamp metav1.Time) *time.Time {
	if timestamp.IsZero() {
		return nil
	}
	created := timestamp.Time
	return &created
}

func listApplicationPods(ctx context.Context, client *Client, namespace, applicationName string) ([]corev1.Pod, error) {
	pods, err := client.Clientset.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationPodSelector(applicationName)})
	if err != nil {
		return nil, err
	}
	return pods.Items, nil
}

// ApplyRuntimeSpec mirrors spec-deploy §4.1.
func (g *RuntimeGateway) ApplyRuntimeSpec(ctx context.Context, environment *domain.Environment, namespace, applicationName string, runtimeSpec domain.RuntimeEnvironmentConfig) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	return TranslateError(applyRuntimeSpec(ctx, client, namespace, applicationName, runtimeSpec))
}

func applyRuntimeSpec(ctx context.Context, client *Client, namespace, applicationName string, runtimeSpec domain.RuntimeEnvironmentConfig) error {
	statefulSets := client.Clientset.AppsV1().StatefulSets(namespace)
	statefulSet, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil
		}
		return err
	}
	if runtimeSpec.Replicas != nil {
		scale := &autoscalingv1.Scale{
			ObjectMeta: metav1.ObjectMeta{Name: applicationName, Namespace: namespace},
			Spec:       autoscalingv1.ScaleSpec{Replicas: int32(*runtimeSpec.Replicas)},
		}
		if _, err := statefulSets.UpdateScale(ctx, applicationName, scale, metav1.UpdateOptions{}); err != nil {
			return err
		}
	}
	requirements := BuildResourceRequirements(runtimeSpec)
	if requirements == nil {
		return nil
	}
	statefulSet, err = statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		return err
	}
	for index := range statefulSet.Spec.Template.Spec.Containers {
		statefulSet.Spec.Template.Spec.Containers[index].Resources = *requirements
	}
	if _, err := statefulSets.Update(ctx, statefulSet, metav1.UpdateOptions{}); err != nil {
		return err
	}
	DeleteRolloutBlockingPods(ctx, client, namespace, ApplicationLabels(applicationName))
	return nil
}

// RolloutRestart mirrors spec-deploy §4.2.
func (g *RuntimeGateway) RolloutRestart(ctx context.Context, environment *domain.Environment, namespace, applicationName string) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	return TranslateError(rolloutRestart(ctx, client, namespace, applicationName, time.Now()))
}

func rolloutRestart(ctx context.Context, client *Client, namespace, applicationName string, now time.Time) error {
	statefulSets := client.Clientset.AppsV1().StatefulSets(namespace)
	statefulSet, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil
		}
		return err
	}
	if statefulSet.Spec.Template.Annotations == nil {
		statefulSet.Spec.Template.Annotations = map[string]string{}
	}
	statefulSet.Spec.Template.Annotations[RestartedAtAnnotation] = formatInstant(now.Truncate(time.Minute))
	if _, err := statefulSets.Update(ctx, statefulSet, metav1.UpdateOptions{}); err != nil {
		return err
	}
	DeleteRolloutBlockingPods(ctx, client, namespace, ApplicationLabels(applicationName))
	slog.Info("Triggered rolling restart", "namespace", namespace, "application", applicationName)
	return nil
}

// DeleteWorkload deletes the StatefulSet (dependents are garbage-collected).
func (g *RuntimeGateway) DeleteWorkload(ctx context.Context, environment *domain.Environment, namespace, applicationName string) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	err = client.Clientset.AppsV1().StatefulSets(namespace).Delete(ctx, applicationName, metav1.DeleteOptions{})
	if err != nil && !IsNotFound(err) {
		return TranslateError(err)
	}
	return nil
}

// RestartPod deletes one pod.
func (g *RuntimeGateway) RestartPod(ctx context.Context, environment *domain.Environment, namespace, podName string) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	return TranslateError(client.Clientset.CoreV1().Pods(namespace).Delete(ctx, podName, metav1.DeleteOptions{}))
}

// PodStatusView mirrors ApplicationPodStatusView.
type PodStatusView struct {
	Name       string                `json:"name"`
	Namespace  string                `json:"namespace"`
	Status     *string               `json:"status"`
	PodIP      *string               `json:"podIP"`
	NodeName   *string               `json:"nodeName"`
	Containers []ContainerStatusView `json:"containers"`
}

// ContainerStatusView mirrors the nested container view.
type ContainerStatusView struct {
	Name         string  `json:"name"`
	Image        string  `json:"image"`
	Ready        bool    `json:"ready"`
	RestartCount int32   `json:"restartCount"`
	StartedAt    *string `json:"startedAt"`
	Reason       *string `json:"reason"`
}

// PodStatusViewOf maps a pod to its status view.
func PodStatusViewOf(pod *corev1.Pod) PodStatusView {
	view := PodStatusView{
		Name:       pod.Name,
		Namespace:  pod.Namespace,
		Status:     domain.StringOrNil(string(pod.Status.Phase)),
		PodIP:      domain.StringOrNil(pod.Status.PodIP),
		NodeName:   domain.StringOrNil(pod.Spec.NodeName),
		Containers: []ContainerStatusView{},
	}
	for _, containerStatus := range pod.Status.ContainerStatuses {
		containerView := ContainerStatusView{
			Name:         containerStatus.Name,
			Image:        containerStatus.Image,
			Ready:        containerStatus.Ready,
			RestartCount: containerStatus.RestartCount,
		}
		if running := containerStatus.State.Running; running != nil && !running.StartedAt.IsZero() {
			containerView.StartedAt = domain.Ptr(formatInstant(running.StartedAt.Time))
		}
		switch {
		case containerStatus.State.Waiting != nil && containerStatus.State.Waiting.Reason != "":
			containerView.Reason = domain.Ptr(containerStatus.State.Waiting.Reason)
		case containerStatus.State.Terminated != nil && containerStatus.State.Terminated.Reason != "":
			containerView.Reason = domain.Ptr(containerStatus.State.Terminated.Reason)
		}
		view.Containers = append(view.Containers, containerView)
	}
	return view
}

// GetPodStatuses lists the application's pods in API order.
func (g *RuntimeGateway) GetPodStatuses(ctx context.Context, environment *domain.Environment, namespace, applicationName string) ([]PodStatusView, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	pods, err := listApplicationPods(ctx, client, namespace, applicationName)
	if err != nil {
		return nil, TranslateError(err)
	}
	views := make([]PodStatusView, 0, len(pods))
	for index := range pods {
		views = append(views, PodStatusViewOf(&pods[index]))
	}
	return views, nil
}

// WatchPodStatuses emits the full pod status snapshot once, then again after
// every ADDED/MODIFIED/DELETED event. Both channels close when ctx ends or the
// watch closes; a watch error is delivered on the error channel first.
func (g *RuntimeGateway) WatchPodStatuses(ctx context.Context, environment *domain.Environment, namespace, applicationName string) (<-chan []PodStatusView, <-chan error, error) {
	client, err := StreamingClient(environment.KubernetesApiServer)
	if err != nil {
		return nil, nil, err
	}
	initial, err := client.Clientset.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationPodSelector(applicationName)})
	if err != nil {
		return nil, nil, TranslateError(err)
	}
	watcher, err := client.Clientset.CoreV1().Pods(namespace).Watch(ctx, metav1.ListOptions{
		LabelSelector:   ApplicationPodSelector(applicationName),
		ResourceVersion: initial.ResourceVersion,
	})
	if err != nil {
		return nil, nil, TranslateError(err)
	}

	snapshots := make(chan []PodStatusView, 1)
	errs := make(chan error, 1)
	go func() {
		defer close(snapshots)
		defer close(errs)
		defer watcher.Stop()

		podsByName := map[string]*corev1.Pod{}
		for index := range initial.Items {
			pod := initial.Items[index]
			podsByName[pod.Name] = &pod
		}
		emit := func() bool {
			select {
			case snapshots <- snapshotViews(podsByName):
				return true
			case <-ctx.Done():
				return false
			}
		}
		if !emit() {
			return
		}
		for {
			select {
			case <-ctx.Done():
				return
			case event, ok := <-watcher.ResultChan():
				if !ok {
					return
				}
				switch event.Type {
				case watch.Added, watch.Modified:
					if pod, isPod := event.Object.(*corev1.Pod); isPod {
						podsByName[pod.Name] = pod
					}
				case watch.Deleted:
					if pod, isPod := event.Object.(*corev1.Pod); isPod {
						delete(podsByName, pod.Name)
					}
				case watch.Error:
					errs <- fmt.Errorf("pod watch error: %v", event.Object)
					return
				default:
					continue
				}
				if !emit() {
					return
				}
			}
		}
	}()
	return snapshots, errs, nil
}

func snapshotViews(podsByName map[string]*corev1.Pod) []PodStatusView {
	views := make([]PodStatusView, 0, len(podsByName))
	for _, name := range sortedKeys(podsByName) {
		views = append(views, PodStatusViewOf(podsByName[name]))
	}
	return views
}

// EventView mirrors ApplicationEventView.
type EventView struct {
	Time         string `json:"time"`
	Type         string `json:"type"`
	ResourceKind string `json:"resourceKind"`
	ResourceName string `json:"resourceName"`
	Reason       string `json:"reason"`
	Message      string `json:"message"`
	Count        int32  `json:"count"`
	instant      time.Time
}

const (
	defaultEventLimit = 200
	maxEventLimit     = 500
)

// GetEvents mirrors spec-deploy §4.7.
func (g *RuntimeGateway) GetEvents(ctx context.Context, environment *domain.Environment, namespace, applicationName string, since *time.Time, limit int) ([]EventView, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	pods, err := listApplicationPods(ctx, client, namespace, applicationName)
	if err != nil {
		return nil, TranslateError(err)
	}
	podNames := map[string]bool{}
	for _, pod := range pods {
		podNames[pod.Name] = true
	}
	events, err := client.Clientset.EventsV1().Events(namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, TranslateError(err)
	}
	return FilterEvents(events.Items, applicationName, podNames, since, limit), nil
}

// FilterEvents keeps the application's events, newest first, capped at limit.
func FilterEvents(events []eventsv1.Event, applicationName string, podNames map[string]bool, since *time.Time, limit int) []EventView {
	effectiveLimit := defaultEventLimit
	if limit > 0 {
		effectiveLimit = min(max(limit, 1), maxEventLimit)
	}
	views := []EventView{}
	for index := range events {
		event := &events[index]
		concerned := eventConcernsApplication(&event.Regarding, applicationName, podNames) ||
			eventConcernsApplication(event.Related, applicationName, podNames)
		if !concerned {
			continue
		}
		instant := eventInstant(event)
		if instant == nil {
			continue
		}
		if since != nil && instant.Before(*since) {
			continue
		}
		views = append(views, EventView{
			Time:         formatInstant(*instant),
			Type:         event.Type,
			ResourceKind: event.Regarding.Kind,
			ResourceName: event.Regarding.Name,
			Reason:       event.Reason,
			Message:      event.Note,
			Count:        eventCount(event),
			instant:      *instant,
		})
	}
	sort.SliceStable(views, func(i, j int) bool { return views[i].instant.After(views[j].instant) })
	if len(views) > effectiveLimit {
		views = views[:effectiveLimit]
	}
	return views
}

func eventConcernsApplication(reference *corev1.ObjectReference, applicationName string, podNames map[string]bool) bool {
	if reference == nil {
		return false
	}
	name := reference.Name
	switch reference.Kind {
	case "Pod":
		return podNames[name] || strings.HasPrefix(name, applicationName+"-")
	case "StatefulSet", "Service", "ConfigMap":
		return name == applicationName
	case "IngressRoute":
		return name == applicationName || strings.HasPrefix(name, applicationName+"-")
	default:
		return false
	}
}

func eventInstant(event *eventsv1.Event) *time.Time {
	if event.Series != nil && !event.Series.LastObservedTime.IsZero() {
		instant := event.Series.LastObservedTime.Time
		return &instant
	}
	if !event.EventTime.IsZero() {
		instant := event.EventTime.Time
		return &instant
	}
	if !event.DeprecatedLastTimestamp.IsZero() {
		instant := event.DeprecatedLastTimestamp.Time
		return &instant
	}
	return creationTime(event.CreationTimestamp)
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

// FindCurrentImage returns the image of the container named after the app,
// else the first container's image, else "" when the StatefulSet is missing.
func (g *RuntimeGateway) FindCurrentImage(ctx context.Context, environment *domain.Environment, namespace, applicationName string) (string, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return "", err
	}
	statefulSet, err := client.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return "", nil
		}
		return "", TranslateError(err)
	}
	return CurrentImageOf(statefulSet, applicationName), nil
}

// CurrentImageOf implements the container lookup of FindCurrentImage.
func CurrentImageOf(statefulSet *appsv1.StatefulSet, applicationName string) string {
	containers := statefulSet.Spec.Template.Spec.Containers
	for _, container := range containers {
		if container.Name == applicationName {
			return container.Image
		}
	}
	if len(containers) > 0 {
		return containers[0].Image
	}
	return ""
}

// FindInternalServiceDomain returns "<svc>.<ns>.svc.cluster.local" for the
// first Service labelled with the application name, or "" when none exists.
func (g *RuntimeGateway) FindInternalServiceDomain(ctx context.Context, environment *domain.Environment, namespace, applicationName string) (string, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return "", err
	}
	services, err := client.Clientset.CoreV1().Services(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationNameSelector(applicationName)})
	if err != nil {
		return "", TranslateError(err)
	}
	if len(services.Items) == 0 {
		return "", nil
	}
	service := services.Items[0]
	return fmt.Sprintf("%s.%s.svc.%s", service.Name, service.Namespace, "cluster.local"), nil
}
