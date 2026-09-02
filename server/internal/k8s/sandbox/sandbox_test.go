package sandbox

import (
	"reflect"
	"strings"
	"testing"

	"github.com/wellch4n/oops/server/internal/domain"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func TestSanitizeLabelValue(t *testing.T) {
	cases := map[string]string{
		"":                             "",
		"python:3.12-slim":             "python_3.12-slim",
		"ghcr.io/acme/tool:v1":         "ghcr.io_acme_tool_v1",
		"__leading.and.trailing__":     "leading.and.trailing",
		"...":                          "",
		"alpine-mate":                  "alpine-mate",
		strings.Repeat("a", 70) + "-b": strings.Repeat("a", 63),
	}
	for input, expected := range cases {
		if actual := SanitizeLabelValue(input); actual != expected {
			t.Errorf("SanitizeLabelValue(%q) = %q, want %q", input, actual, expected)
		}
	}
}

func TestBuiltinRuntimes(t *testing.T) {
	if got := BuiltinRuntimes(); !reflect.DeepEqual(got, []string{"alpine-mate"}) {
		t.Fatalf("BuiltinRuntimes() = %v", got)
	}
	if !IsBuiltin("alpine-mate") || IsBuiltin("linuxserver/webtop:alpine-mate") || IsBuiltin("Alpine-Mate") {
		t.Fatal("IsBuiltin must match the key exactly")
	}
}

func TestBuildJob(t *testing.T) {
	spec := JobSpec{
		Image: "python:3.12-slim", Script: "echo a\necho b",
		TimeoutSeconds: 300, TTLSecondsAfterFinished: 60,
		CPURequest: "100m", CPULimit: "1", MemoryRequest: "128Mi", MemoryLimit: "512Mi",
		Env:             []EnvVar{{"ZED", "1"}, {"ALPHA", "2"}, {"MID", ""}},
		CreatedByUserID: "user-1",
	}
	job, err := buildJob(spec, "abc", "oops-work")
	if err != nil {
		t.Fatal(err)
	}
	if job.Name != "oops-sandbox-abc" || job.Namespace != "oops-work" {
		t.Fatalf("unexpected metadata %s/%s", job.Namespace, job.Name)
	}
	wantLabels := map[string]string{"oops.type": "sandbox", "oops.sandbox.kind": "ephemeral", "oops.sandbox.id": "abc", "oops.sandbox.created-by": "user-1"}
	if !reflect.DeepEqual(job.Labels, wantLabels) || !reflect.DeepEqual(job.Spec.Template.Labels, wantLabels) {
		t.Fatalf("labels = %v / %v", job.Labels, job.Spec.Template.Labels)
	}
	if *job.Spec.BackoffLimit != 0 || *job.Spec.ActiveDeadlineSeconds != 300 || *job.Spec.TTLSecondsAfterFinished != 60 {
		t.Fatal("job timing fields differ from spec")
	}
	pod := job.Spec.Template.Spec
	if pod.RestartPolicy != corev1.RestartPolicyNever || pod.ImagePullSecrets[0].Name != "dockerhub" {
		t.Fatal("pod spec restart policy / pull secret differ")
	}
	container := pod.Containers[0]
	if container.Name != "sandbox" || container.ImagePullPolicy != corev1.PullAlways || !*container.SecurityContext.Privileged {
		t.Fatal("container basics differ")
	}
	if !reflect.DeepEqual(container.Command, []string{"/bin/sh", "-c", "echo a\necho b"}) {
		t.Fatalf("command = %v", container.Command)
	}
	wantEnv := []corev1.EnvVar{{Name: "ZED", Value: "1"}, {Name: "ALPHA", Value: "2"}, {Name: "MID", Value: ""}}
	if !reflect.DeepEqual(container.Env, wantEnv) {
		t.Fatalf("env order not preserved: %v", container.Env)
	}
	if container.Resources.Requests.Memory().String() != "128Mi" || container.Resources.Limits.Cpu().String() != "1" {
		t.Fatal("ephemeral memory must be used as given")
	}

	// Without a creator the label is absent entirely.
	spec.CreatedByUserID = " "
	job, _ = buildJob(spec, "abc", "oops-work")
	if _, present := job.Labels[LabelCreatedBy]; present {
		t.Fatal("blank creator must not produce a label")
	}

	spec.CPURequest = "lots"
	if _, err := buildJob(spec, "abc", "oops-work"); err == nil {
		t.Fatal("invalid quantity must be rejected")
	}
}

func TestBuildPersistentStatefulSet(t *testing.T) {
	spec := PersistentSpec{
		SandboxID: "id1", Name: "dev box", Image: "ghcr.io/acme/tool:v1",
		CPURequest: domain.Ptr("250m"), MemoryLimit: domain.Ptr("512"),
		Env: []EnvVar{{"B", "2"}, {"A", "1"}}, CreatedByUserID: "u1", UseDefaultKeepalive: true,
	}
	statefulSet, err := buildPersistentStatefulSet(spec, "work")
	if err != nil {
		t.Fatal(err)
	}
	wantLabels := map[string]string{
		"oops.type": "sandbox", "oops.sandbox.kind": "persistent", "oops.sandbox.id": "id1",
		"oops.sandbox.image": "ghcr.io_acme_tool_v1", "oops.sandbox.created-by": "u1",
	}
	if !reflect.DeepEqual(statefulSet.Labels, wantLabels) || !reflect.DeepEqual(statefulSet.Spec.Selector.MatchLabels, wantLabels) {
		t.Fatalf("labels/selector = %v / %v", statefulSet.Labels, statefulSet.Spec.Selector.MatchLabels)
	}
	wantAnnotations := map[string]string{
		"oops.sandbox.name": "dev box", "oops.sandbox.image": "ghcr.io/acme/tool:v1",
		"oops.sandbox.cpu-request": "250m", "oops.sandbox.memory-limit": "512",
	}
	if !reflect.DeepEqual(statefulSet.Annotations, wantAnnotations) {
		t.Fatalf("annotations = %v", statefulSet.Annotations)
	}
	if statefulSet.Spec.ServiceName != "oops-sandbox-id1" || *statefulSet.Spec.Replicas != 1 {
		t.Fatal("serviceName/replicas differ")
	}
	container := statefulSet.Spec.Template.Spec.Containers[0]
	if !reflect.DeepEqual(container.Command, []string{"/bin/sh", "-c", "trap : TERM INT; sleep infinity & wait"}) || container.Stdin || container.TTY {
		t.Fatalf("keepalive command expected, got %v stdin=%v tty=%v", container.Command, container.Stdin, container.TTY)
	}
	if container.Resources.Limits.Memory().String() != "512Mi" {
		t.Fatalf("memory limit = %s, want 512Mi", container.Resources.Limits.Memory().String())
	}
	if _, present := container.Resources.Requests[corev1.ResourceMemory]; present {
		t.Fatal("absent memory request must be omitted")
	}
	if _, present := container.Resources.Limits[corev1.ResourceCPU]; present {
		t.Fatal("absent cpu limit must be omitted")
	}
	if !reflect.DeepEqual(container.Env, []corev1.EnvVar{{Name: "B", Value: "2"}, {Name: "A", Value: "1"}}) {
		t.Fatalf("env = %v", container.Env)
	}

	// Verbatim "Mi" suffix parity: "512Mi" -> "512MiMi", which is not a valid quantity.
	spec.MemoryLimit = domain.Ptr("512Mi")
	if _, err := buildPersistentStatefulSet(spec, "work"); err == nil {
		t.Fatal("double Mi suffix should fail to parse")
	}

	spec.MemoryLimit = nil
	spec.UseDefaultKeepalive = false
	statefulSet, _ = buildPersistentStatefulSet(spec, "work")
	container = statefulSet.Spec.Template.Spec.Containers[0]
	if container.Command != nil || !container.Stdin || !container.TTY {
		t.Fatal("without keepalive the container must run with stdin+tty and no command override")
	}
}

func TestBuildAlpineMateStatefulSetAndService(t *testing.T) {
	spec := PersistentSpec{SandboxID: "id2", Name: "desk", Image: "alpine-mate", Env: []EnvVar{{"TZ", "UTC"}, {"EXTRA", "x"}}}
	statefulSet, err := buildAlpineMateStatefulSet(spec, "work")
	if err != nil {
		t.Fatal(err)
	}
	if statefulSet.Labels["oops.sandbox.image"] != "alpine-mate" || statefulSet.Annotations["oops.sandbox.image"] != "alpine-mate" {
		t.Fatal("builtin key must be used for both the image label and annotation")
	}
	if !reflect.DeepEqual(statefulSet.Spec.Selector.MatchLabels, map[string]string{"oops.sandbox.id": "id2"}) {
		t.Fatalf("selector = %v", statefulSet.Spec.Selector.MatchLabels)
	}
	container := statefulSet.Spec.Template.Spec.Containers[0]
	if container.Image != "linuxserver/webtop:alpine-mate" || container.SecurityContext.SeccompProfile.Type != corev1.SeccompProfileTypeUnconfined {
		t.Fatal("alpine-mate image / seccomp differ")
	}
	wantEnv := []corev1.EnvVar{
		{Name: "PUID", Value: "1000"}, {Name: "PGID", Value: "1000"}, {Name: "TZ", Value: "UTC"},
		{Name: "SUBFOLDER", Value: "/"}, {Name: "TITLE", Value: "desk"}, {Name: "EXTRA", Value: "x"},
	}
	if !reflect.DeepEqual(container.Env, wantEnv) {
		t.Fatalf("env = %v", container.Env)
	}
	if len(container.Ports) != 2 || container.Ports[1].ContainerPort != 3001 {
		t.Fatal("ports differ")
	}
	dshm := statefulSet.Spec.Template.Spec.Volumes[1]
	if dshm.EmptyDir.Medium != corev1.StorageMediumMemory || dshm.EmptyDir.SizeLimit.String() != "1Gi" {
		t.Fatal("dshm volume differs")
	}

	service := buildAlpineMateService("id2", "work", "uid-123")
	owner := service.OwnerReferences[0]
	if owner.Kind != "StatefulSet" || string(owner.UID) != "uid-123" || owner.Controller != nil || !*owner.BlockOwnerDeletion {
		t.Fatalf("owner reference = %+v", owner)
	}
	if service.Spec.Type != corev1.ServiceTypeClusterIP || service.Spec.Ports[0].TargetPort.IntValue() != 3000 || service.Spec.Ports[1].Port != 3001 {
		t.Fatal("service ports differ")
	}
}

func TestDeriveStatus(t *testing.T) {
	now := metav1.Now()
	terminating := &appsv1.StatefulSet{ObjectMeta: metav1.ObjectMeta{DeletionTimestamp: &now}}
	live := &appsv1.StatefulSet{}
	podWith := func(statuses ...corev1.ContainerStatus) *corev1.Pod {
		return &corev1.Pod{Status: corev1.PodStatus{ContainerStatuses: statuses}}
	}
	waiting := func(reason string) corev1.ContainerStatus {
		return corev1.ContainerStatus{State: corev1.ContainerState{Waiting: &corev1.ContainerStateWaiting{Reason: reason}}}
	}

	cases := []struct {
		name        string
		statefulSet *appsv1.StatefulSet
		pod         *corev1.Pod
		want        domain.SandboxInstanceStatus
	}{
		{"deleting wins even with a ready pod", terminating, podWith(corev1.ContainerStatus{Ready: true}), domain.SandboxTerminating},
		{"no pod", live, nil, domain.SandboxPending},
		{"no container statuses", live, podWith(), domain.SandboxPending},
		{"all ready", live, podWith(corev1.ContainerStatus{Ready: true}, corev1.ContainerStatus{Ready: true}), domain.SandboxRunning},
		{"crash loop", live, podWith(waiting("CrashLoopBackOff")), domain.SandboxFailed},
		{"image pull backoff", live, podWith(corev1.ContainerStatus{Ready: true}, waiting("ImagePullBackOff")), domain.SandboxFailed},
		{"err image pull", live, podWith(waiting("ErrImagePull")), domain.SandboxFailed},
		{"config error", live, podWith(waiting("CreateContainerConfigError")), domain.SandboxFailed},
		{"still creating", live, podWith(waiting("ContainerCreating")), domain.SandboxPending},
		{"not ready, no waiting", live, podWith(corev1.ContainerStatus{Ready: false}), domain.SandboxPending},
	}
	for _, testCase := range cases {
		if got := deriveStatus(testCase.statefulSet, testCase.pod); got != testCase.want {
			t.Errorf("%s: deriveStatus = %s, want %s", testCase.name, got, testCase.want)
		}
	}
}

func TestSortByCreatedAtDesc(t *testing.T) {
	instances := []domain.SandboxInstance{
		{ID: "old", CreatedAt: domain.Ptr("2026-01-01T00:00:00Z")},
		{ID: "none"},
		{ID: "new", CreatedAt: domain.Ptr("2026-06-01T00:00:00Z")},
	}
	sortByCreatedAtDesc(instances)
	var order []string
	for _, instance := range instances {
		order = append(order, instance.ID)
	}
	if !reflect.DeepEqual(order, []string{"new", "old", "none"}) {
		t.Fatalf("order = %v", order)
	}
}

func TestLineWriter(t *testing.T) {
	var lines []string
	writer := newLineWriter(func(line string) { lines = append(lines, line) })
	writer.Write([]byte("first\nsec"))
	writer.Write([]byte("ond\n\ntail"))
	if !reflect.DeepEqual(lines, []string{"first", "second", ""}) {
		t.Fatalf("lines before flush = %q", lines)
	}
	writer.Flush()
	writer.Flush()
	if !reflect.DeepEqual(lines, []string{"first", "second", "", "tail"}) {
		t.Fatalf("lines after flush = %q", lines)
	}
}

func TestReadExitCode(t *testing.T) {
	if readExitCode(nil) != -1 || readExitCode(&corev1.Pod{}) != -1 {
		t.Fatal("missing status must read as -1")
	}
	pod := &corev1.Pod{Status: corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{ExitCode: 7}}}}}}
	if readExitCode(pod) != 7 {
		t.Fatal("terminated exit code must be returned")
	}
}
