package engine

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/wellch4n/oops/server/internal/store"
)

// Mirrors CloneStrategyTests + PublishContainerTests.

func testImages() ImageConfig {
	return ImageConfig{
		Clone: "alpine/git:2.43.0",
		Zip:   "alpine/curl:8.17.0",
		Push:  "quay.io/buildah/stable:v1.35",
	}
}

func gitInput() *buildJobInput {
	return &buildJobInput{
		PipelineID:      "abc123",
		PipelineName:    "demo-pipeline-abc123",
		Namespace:       "default",
		ApplicationName: "demo",
		WorkNamespace:   "oops-work",
		RepositoryHost:  "registry.example.com",
		Git:             &store.GitPublishConfig{Repository: "https://github.com/acme/demo.git", Branch: "main"},
		Images:          testImages(),
	}
}

func containerNames(input *buildJobInput, t *testing.T) []string {
	t.Helper()
	job, _, err := buildPipelineJob(input)
	if err != nil {
		t.Fatalf("buildPipelineJob: %v", err)
	}
	names := []string{}
	for _, container := range job.Spec.Template.Spec.InitContainers {
		names = append(names, container.Name)
	}
	return names
}

func TestGitCloneCommand(t *testing.T) {
	command, err := gitCloneCommand(&store.GitPublishConfig{Repository: "https://github.com/acme/demo.git", Branch: "release"})
	if err != nil {
		t.Fatal(err)
	}
	want := "git clone --progress --depth 1 -b release https://github.com/acme/demo.git /workspace"
	if command != want {
		t.Errorf("got %q, want %q", command, want)
	}

	command, _ = gitCloneCommand(&store.GitPublishConfig{Repository: "https://github.com/acme/demo.git"})
	if strings.Contains(command, " -b ") {
		t.Errorf("empty branch must not add -b: %q", command)
	}

	if _, err := gitCloneCommand(nil); err == nil {
		t.Error("nil git config must be rejected")
	}
	if _, err := gitCloneCommand(&store.GitPublishConfig{}); err == nil {
		t.Error("empty repository must be rejected")
	}
}

func TestZipCloneCommand(t *testing.T) {
	command := zipCloneCommand("https://s3.example.com/presigned", []string{"__MACOSX/*", "*.git/*"})
	for _, fragment := range []string{
		"curl -fL", "'https://s3.example.com/presigned'",
		"504b", // ZIP magic check
		"-x '__MACOSX/*' '*.git/*'",
	} {
		if !strings.Contains(command, fragment) {
			t.Errorf("zip command missing %q", fragment)
		}
	}
}

func TestBuildJobMinimalSteps(t *testing.T) {
	names := containerNames(gitInput(), t)
	want := []string{"fetch", "publish"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Errorf("init containers = %v, want %v", names, want)
	}
}

func TestBuildJobAllSteps(t *testing.T) {
	dockerFileType, content := "USER", "FROM nginx:alpine"
	input := gitInput()
	input.DockerFile = &store.DockerFileConfig{Type: &dockerFileType, Content: &content}
	input.BuildImage = "maven:3.9"
	input.BuildCommand = "mvn package"
	names := containerNames(input, t)
	want := []string{"fetch", "dockerfile", "compile", "publish"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Errorf("init containers = %v, want %v", names, want)
	}
}

func TestBuildJobInlineDockerfileIsBase64(t *testing.T) {
	dockerFileType, content := "USER", "FROM nginx:alpine\nCOPY . /srv"
	input := gitInput()
	input.DockerFile = &store.DockerFileConfig{Type: &dockerFileType, Content: &content}
	job, _, err := buildPipelineJob(input)
	if err != nil {
		t.Fatal(err)
	}
	command := strings.Join(job.Spec.Template.Spec.InitContainers[1].Command, " ")
	if !strings.Contains(command, base64.StdEncoding.EncodeToString([]byte(content))) {
		t.Error("inline Dockerfile content must be shipped base64-encoded")
	}
}

func TestBuildJobBuiltinDockerfilePath(t *testing.T) {
	dockerFileType, path := "BUILTIN", "docker/Dockerfile.prod"
	input := gitInput()
	input.DockerFile = &store.DockerFileConfig{Type: &dockerFileType, Path: &path}
	job, _, err := buildPipelineJob(input)
	if err != nil {
		t.Fatal(err)
	}
	publish := job.Spec.Template.Spec.InitContainers[len(job.Spec.Template.Spec.InitContainers)-1]
	found := false
	for _, argument := range publish.Command {
		if argument == path {
			found = true
		}
	}
	if !found {
		t.Errorf("publish command must carry the BUILTIN dockerfile path, got %v", publish.Command)
	}
}

func TestBuildJobArtifactReference(t *testing.T) {
	_, artifact, err := buildPipelineJob(gitInput())
	if err != nil {
		t.Fatal(err)
	}
	if artifact != "registry.example.com/demo:abc123" {
		t.Errorf("artifact = %q", artifact)
	}
}

func TestBuildJobZipSource(t *testing.T) {
	url := "https://s3.example.com/presigned"
	input := gitInput()
	input.Git = nil
	input.Zip = &store.ZipPublishConfig{URL: &url}
	job, _, err := buildPipelineJob(input)
	if err != nil {
		t.Fatal(err)
	}
	fetch := job.Spec.Template.Spec.InitContainers[0]
	if fetch.Image != "alpine/curl:8.17.0" {
		t.Errorf("zip fetch image = %q", fetch.Image)
	}

	input.Images.Zip = ""
	if _, _, err := buildPipelineJob(input); err == nil {
		t.Error("zip source without a zip image must be rejected")
	}
}

func TestBuildJobHardening(t *testing.T) {
	job, _, err := buildPipelineJob(gitInput())
	if err != nil {
		t.Fatal(err)
	}
	spec := job.Spec
	if *spec.BackoffLimit != 0 {
		t.Error("build jobs must not retry")
	}
	if *spec.TTLSecondsAfterFinished != 3*24*60*60 {
		t.Error("finished jobs must be garbage-collected after 3 days")
	}
	pod := spec.Template.Spec
	if pod.RestartPolicy != "Never" {
		t.Errorf("restart policy = %q", pod.RestartPolicy)
	}
	publish := pod.InitContainers[len(pod.InitContainers)-1]
	if publish.SecurityContext == nil || publish.SecurityContext.Privileged == nil || !*publish.SecurityContext.Privileged {
		t.Error("buildah publish container must be privileged")
	}
	if job.Labels["oops.type"] != "PIPELINE" {
		t.Errorf("labels = %v", job.Labels)
	}
}

func TestBuildRegistriesConf(t *testing.T) {
	conf := buildRegistriesConf(map[string]string{
		"index.docker.io": "https://mirror.example.com", // invalid location: scheme
		"docker.io":       "mirror.example.com",
		"quay.io":         "quay-mirror.example.com",
		"bad prefix":      "mirror.example.com",
		"":                "mirror.example.com",
	})
	if !strings.HasPrefix(conf, "unqualified-search-registries = [\"docker.io\"]") {
		t.Errorf("missing search registries header: %q", conf)
	}
	if !strings.Contains(conf, "prefix = \"docker.io\"") || !strings.Contains(conf, "location = \"mirror.example.com\"") {
		t.Errorf("docker.io mirror missing: %q", conf)
	}
	if !strings.Contains(conf, "quay-mirror.example.com") {
		t.Errorf("quay mirror missing: %q", conf)
	}
	if strings.Contains(conf, "bad prefix") || strings.Contains(conf, "https://mirror.example.com") {
		t.Errorf("invalid entries must be skipped: %q", conf)
	}
}
