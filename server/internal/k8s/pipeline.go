package k8s

import (
	"context"
	"encoding/base64"
	"fmt"
	"regexp"
	"sort"
	"strings"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/domain"
)

// The build runs as one Job whose init containers are the pipeline's steps, in
// order: fetch, [dockerfile], [compile], publish. Init containers give the steps
// their sequencing and their all-or-nothing failure for free, and the log
// streamer names each step after the container it came from. The Job's single
// real container only exists because a Pod must have one.
const (
	workspacePath = "/workspace"
	// containerStorePath is where buildah keeps its store. It has to be an
	// explicit mount: buildah's own image declares a VOLUME here, and Kubernetes
	// ignores image VOLUME declarations.
	containerStorePath = "/var/lib/containers"
	// registryAuthPath is where the pull secret is projected for buildah push.
	registryAuthPath = "/var/buildah/.docker"

	doneImage = "busybox:1.36.1"

	// buildDeadlineSeconds caps a runaway build at two hours.
	buildDeadlineSeconds = 2 * 60 * 60
	// buildTTLSeconds keeps a finished Job for three days, so its logs survive
	// long enough to look at.
	buildTTLSeconds = 3 * 24 * 60 * 60
)

// PipelineJobInput is everything the build Job is derived from.
type PipelineJobInput struct {
	Pipeline    *domain.Pipeline
	Application *domain.Application
	BuildConfig *domain.ApplicationBuildConfig
	Environment *domain.Environment

	CloneImage string
	ZipImage   string
	PushImage  string

	RegistryMirrors map[string]string
	UnzipExcludes   []string

	// SourceDownloadURL is the presigned URL for a ZIP build. It is resolved at
	// submit time rather than stored, so an object key can never go stale.
	SourceDownloadURL string
}

// StartPipelineJob builds the Job and creates it in the work namespace. It
// returns the artifact reference the publish step will push.
func StartPipelineJob(ctx context.Context, client *Client, in PipelineJobInput) (string, error) {
	job, artifact, err := BuildPipelineJob(in)
	if err != nil {
		return "", err
	}
	_, err = client.Clientset.BatchV1().Jobs(domain.Deref(in.Environment.WorkNamespace)).
		Create(ctx, job, metav1.CreateOptions{FieldManager: FieldManager})
	if err != nil {
		return "", fmt.Errorf("create pipeline job %s: %w", in.Pipeline.Name(), TranslateError(err))
	}
	return artifact, nil
}

// BuildPipelineJob assembles the Job and the artifact reference without talking
// to a cluster, which is what makes the whole step layout testable.
func BuildPipelineJob(in PipelineJobInput) (*batchv1.Job, string, error) {
	if in.BuildConfig == nil {
		return nil, "", domain.Biz("Application build config not found.")
	}
	workspace, workspaceMounts := workspaceVolume()
	secrets, secretMounts := buildSecretVolumes()
	store, storeMounts := containerStorageVolume()

	fetch, err := fetchContainer(in)
	if err != nil {
		return nil, "", err
	}
	fetch.VolumeMounts = append(workspaceMounts, secretMounts...)
	steps := []corev1.Container{fetch}

	dockerFile := in.BuildConfig.DockerFileConfig
	if dockerFile != nil && dockerFile.Type == domain.DockerFileUser {
		step := dockerfileContainer(domain.Deref(dockerFile.Content), in.CloneImage)
		step.VolumeMounts = workspaceMounts
		steps = append(steps, step)
	}

	buildCommand := buildCommandFor(in.BuildConfig, in.Environment.Name)
	if domain.Deref(in.BuildConfig.BuildImage) != "" && buildCommand != "" {
		step := compileContainer(domain.Deref(in.BuildConfig.BuildImage), buildCommand)
		step.VolumeMounts = workspaceMounts
		steps = append(steps, step)
	}

	artifact := artifactReference(in)
	publish := publishContainer(in, artifact)
	publish.VolumeMounts = append(append(append([]corev1.VolumeMount{}, workspaceMounts...), secretMounts...), storeMounts...)
	steps = append(steps, publish)

	done := corev1.Container{
		Name:         "done",
		Image:        doneImage,
		Command:      []string{"sh", "-c", "echo done!"},
		VolumeMounts: append(append([]corev1.VolumeMount{}, workspaceMounts...), secretMounts...),
	}

	labels := map[string]string{
		domain.LabelType:                 domain.TypePipeline,
		"oops.pipeline.id":               in.Pipeline.ID,
		"oops.pipeline.name":             in.Pipeline.Name(),
		"oops.pipeline.application.name": in.Application.Name,
	}
	deadline := int64(buildDeadlineSeconds)
	ttl := int32(buildTTLSeconds)
	backoff := int32(0)

	job := &batchv1.Job{
		TypeMeta: metav1.TypeMeta{APIVersion: "batch/v1", Kind: "Job"},
		ObjectMeta: metav1.ObjectMeta{
			Name:      in.Pipeline.Name(),
			Namespace: domain.Deref(in.Environment.WorkNamespace),
			Labels:    labels,
		},
		Spec: batchv1.JobSpec{
			BackoffLimit:            &backoff,
			ActiveDeadlineSeconds:   &deadline,
			TTLSecondsAfterFinished: &ttl,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					InitContainers: steps,
					Containers:     []corev1.Container{done},
					RestartPolicy:  corev1.RestartPolicyNever,
					Volumes:        append(append(workspace, secrets...), store...),
					// Builds are heavy and bursty, so spread them over the nodes
					// rather than letting them pile onto whichever one is emptiest.
					// ScheduleAnyway: a build must never be unschedulable over this.
					TopologySpreadConstraints: []corev1.TopologySpreadConstraint{{
						MaxSkew:           1,
						TopologyKey:       "kubernetes.io/hostname",
						WhenUnsatisfiable: corev1.ScheduleAnyway,
						LabelSelector: &metav1.LabelSelector{
							MatchLabels: map[string]string{domain.LabelType: domain.TypePipeline},
						},
					}},
				},
			},
		},
	}
	return job, artifact, nil
}

// artifactReference is registry/application:pipelineId, with any scheme stripped
// off the registry — buildah wants a bare host.
func artifactReference(in PipelineJobInput) string {
	registry := ""
	if in.Environment.ImageRepository != nil {
		registry = domain.Deref(in.Environment.ImageRepository.URL)
	}
	registry = strings.TrimPrefix(strings.TrimPrefix(registry, "https://"), "http://")
	return fmt.Sprintf("%s/%s:%s", registry, in.Application.Name, in.Pipeline.ID)
}

// buildCommandFor picks the environment's build command out of the build config.
func buildCommandFor(config *domain.ApplicationBuildConfig, environment string) string {
	for _, item := range config.EnvironmentConfigs {
		if domain.Deref(item.Environment) == environment {
			return domain.Deref(item.BuildCommand)
		}
	}
	return ""
}

// ---------------------------------------------------------------------------
// steps

func fetchContainer(in PipelineJobInput) (corev1.Container, error) {
	sourceType := in.Pipeline.PublishType
	if sourceType == "" {
		sourceType = in.BuildConfig.EffectiveSourceType()
	}
	image := in.CloneImage
	command := ""
	if sourceType == domain.SourceZip {
		if strings.TrimSpace(in.ZipImage) == "" {
			return corev1.Container{}, domain.Biz("ZIP source requires pipeline.images.zip to be configured")
		}
		if strings.TrimSpace(in.SourceDownloadURL) == "" {
			return corev1.Container{}, domain.Bizf("ZIP pipeline is missing its source URL: %s", in.Pipeline.Name())
		}
		image = in.ZipImage
		command = zipFetchCommand(in.SourceDownloadURL, in.UnzipExcludes)
	} else {
		if in.Pipeline.PublishConfig == nil || strings.TrimSpace(domain.Deref(in.Pipeline.PublishConfig.Repository)) == "" {
			return corev1.Container{}, domain.Bizf("GIT pipeline is missing its publish config: %s", in.Pipeline.Name())
		}
		command = gitCloneCommand(domain.Deref(in.Pipeline.PublishConfig.Repository), domain.Deref(in.Pipeline.PublishConfig.Branch))
	}
	return corev1.Container{
		Name:    "fetch",
		Image:   image,
		Command: []string{"sh", "-c", command},
		Env: []corev1.EnvVar{{
			Name:  "GIT_SSH_COMMAND",
			Value: "ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR",
		}},
	}, nil
}

// gitCloneCommand is always shallow: a build only ever needs the one commit.
func gitCloneCommand(repository, branch string) string {
	args := []string{"git", "clone", "--progress", "--depth", "1"}
	if strings.TrimSpace(branch) != "" {
		args = append(args, "-b", branch)
	}
	return strings.Join(append(args, repository, workspacePath), " ")
}

// zipFetchCommand downloads and unpacks the archive. It rejects a non-ZIP file
// up front, because unzip's own message for one is unhelpful, and it unwraps a
// single top-level directory so that an archive made by "zip -r x.zip project"
// lays out the same as one made from inside the project.
func zipFetchCommand(downloadURL string, excludes []string) string {
	quoted := make([]string, 0, len(excludes))
	for _, exclude := range excludes {
		quoted = append(quoted, "'"+exclude+"'")
	}
	return fmt.Sprintf(`set -e
rm -rf %[1]s/* /tmp/source-download /tmp/source.zip
mkdir -p %[1]s /tmp/source-download
curl -fL --connect-timeout 30 --max-time 120 '%[2]s' -o /tmp/source.zip
if [ ! -s /tmp/source.zip ]; then
    echo "Downloaded file is empty" >&2
    exit 1
fi
magic=$(od -A n -t x1 -N 2 /tmp/source.zip 2>/dev/null | sed 's/ //g')
if [ "$magic" != "504b" ]; then
    echo "Downloaded file is not a valid ZIP archive" >&2
    exit 1
fi
unzip -o /tmp/source.zip -d /tmp/source-download -x %[3]s
find /tmp/source-download -mindepth 1 -maxdepth 1 \
  ! -name '__MACOSX' \
  ! -name '.DS_Store' \
  > /tmp/source-entries
first_entry="$(head -n 1 /tmp/source-entries)"
entry_count="$(wc -l < /tmp/source-entries | tr -d ' ')"
if [ "$entry_count" = "1" ] && [ -d "$first_entry" ]; then
  cp -a "$first_entry"/. %[1]s/
else
  cp -a /tmp/source-download/. %[1]s/
  rm -rf %[1]s/__MACOSX %[1]s/.DS_Store
fi
`, workspacePath, downloadURL, strings.Join(quoted, " "))
}

// dockerfileContainer writes an inline Dockerfile into the workspace. The
// content travels base64-encoded so no quoting or newline in it can break out
// of the shell command.
func dockerfileContainer(content, image string) corev1.Container {
	encoded := base64.StdEncoding.EncodeToString([]byte(content))
	command := "echo 'Writing custom Dockerfile' && printf '%s' " + encoded +
		" | base64 -d > " + workspacePath + "/Dockerfile && echo 'Custom Dockerfile written' && wc -c " + workspacePath + "/Dockerfile"
	return corev1.Container{
		Name:       "dockerfile",
		Image:      image,
		WorkingDir: workspacePath,
		Command:    []string{"sh", "-c", command},
	}
}

func compileContainer(image, command string) corev1.Container {
	return corev1.Container{
		Name:       "compile",
		Image:      image,
		WorkingDir: workspacePath,
		Command:    []string{"sh", "-c", command},
	}
}

// publishContainer runs buildah with the overlay driver over the emptyDir
// mounted at containerStorePath. Both halves matter: the store defaults into the
// container's writable layer, where every write pays an overlayfs copy-up, and
// vfs copies the entire tree per layer — together they made a build of any size
// stall for minutes with no log output after its last line.
func publishContainer(in PipelineJobInput, artifact string) corev1.Container {
	registriesConf := base64.StdEncoding.EncodeToString([]byte(buildRegistriesConf(in.RegistryMirrors)))
	privileged := true
	command := `printf '%s' "$3" | base64 -d > /tmp/registries.conf
buildah bud --storage-driver=overlay --tls-verify=false --isolation chroot --registries-conf /tmp/registries.conf -t "$1" -f "$2" ` + workspacePath + `
buildah push --storage-driver=overlay --tls-verify=false --registries-conf /tmp/registries.conf "$1"
`
	return corev1.Container{
		Name:       "publish",
		Image:      in.PushImage,
		WorkingDir: workspacePath,
		Command: []string{"sh", "-eu", "-c", command,
			"publish", artifact, dockerfilePath(in.BuildConfig), registriesConf},
		Env:             []corev1.EnvVar{{Name: "REGISTRY_AUTH_FILE", Value: registryAuthPath + "/config.json"}},
		SecurityContext: &corev1.SecurityContext{Privileged: &privileged},
	}
}

// dockerfilePath is the -f argument: an inline Dockerfile was written to the
// workspace root, otherwise the configured path, otherwise "Dockerfile".
func dockerfilePath(config *domain.ApplicationBuildConfig) string {
	const defaultDockerfile = "Dockerfile"
	if config == nil || config.DockerFileConfig == nil {
		return defaultDockerfile
	}
	if config.DockerFileConfig.Type == domain.DockerFileUser {
		return defaultDockerfile
	}
	if path := strings.TrimSpace(domain.Deref(config.DockerFileConfig.Path)); path != "" {
		return path
	}
	return defaultDockerfile
}

// registryLocation is buildah's accepted shape for a registry prefix or mirror.
var registryLocation = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._:-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)*$`)

// buildRegistriesConf renders the mirror map as buildah's registries.conf TOML.
// A malformed entry is skipped rather than failing the build: one bad mirror in
// the config should not stop every application from being deployable.
func buildRegistriesConf(mirrors map[string]string) string {
	var conf strings.Builder
	conf.WriteString("unqualified-search-registries = [\"docker.io\"]\n\n")
	prefixes := make([]string, 0, len(mirrors))
	for prefix := range mirrors {
		prefixes = append(prefixes, prefix)
	}
	// Sorted, so the same config always produces the same container spec.
	sort.Strings(prefixes)
	for _, key := range prefixes {
		prefix := strings.TrimSpace(key)
		mirror := strings.TrimSpace(mirrors[key])
		if prefix == "" || mirror == "" {
			continue
		}
		if prefix == "index.docker.io" {
			prefix = "docker.io"
		}
		if !registryLocation.MatchString(prefix) || !registryLocation.MatchString(mirror) {
			continue
		}
		fmt.Fprintf(&conf, "[[registry]]\nprefix = %q\nlocation = %q\n\n[[registry.mirror]]\nlocation = %q\n\n", prefix, prefix, mirror)
	}
	return conf.String()
}

// ---------------------------------------------------------------------------
// volumes

func workspaceVolume() ([]corev1.Volume, []corev1.VolumeMount) {
	return []corev1.Volume{{
		Name:         "workspace",
		VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}},
	}}, []corev1.VolumeMount{{
		Name: "workspace", MountPath: workspacePath,
	}}
}

func containerStorageVolume() ([]corev1.Volume, []corev1.VolumeMount) {
	return []corev1.Volume{{
		Name:         "container-storage",
		VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}},
	}}, []corev1.VolumeMount{{
		Name: "container-storage", MountPath: containerStorePath,
	}}
}

// buildSecretVolumes projects the registry and git credentials. Both are
// optional: an environment whose registry needs no credentials never gets a
// dockerhub Secret, and without `optional` the build pod would be the one place
// that refuses to start — failing as an unexplained FailedMount.
func buildSecretVolumes() ([]corev1.Volume, []corev1.VolumeMount) {
	optional := true
	sshKeyMode := int32(0o600)
	volumes := []corev1.Volume{
		{
			Name: "registry-secret",
			VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{
				SecretName: ImagePullSecretName,
				Optional:   &optional,
				Items:      []corev1.KeyToPath{{Key: corev1.DockerConfigJsonKey, Path: "config.json"}},
			}},
		},
		{
			Name: "git-secret",
			VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{
				SecretName:  GitCredentialSecretName,
				Optional:    &optional,
				DefaultMode: &sshKeyMode,
				Items: []corev1.KeyToPath{
					{Key: ".netrc", Path: ".netrc"},
					{Key: "id_rsa", Path: "id_rsa"},
				},
			}},
		},
	}
	mounts := []corev1.VolumeMount{
		{Name: "registry-secret", MountPath: registryAuthPath},
		{Name: "git-secret", MountPath: "/root/.netrc", SubPath: ".netrc"},
		{Name: "git-secret", MountPath: "/root/.ssh/id_rsa", SubPath: "id_rsa"},
	}
	return volumes, mounts
}

// DeletePipelineJob removes a build Job and its pods, for a stop request.
func DeletePipelineJob(ctx context.Context, client *Client, workNamespace, jobName string) error {
	policy := metav1.DeletePropagationBackground
	err := client.Clientset.BatchV1().Jobs(workNamespace).
		Delete(ctx, jobName, metav1.DeleteOptions{PropagationPolicy: &policy})
	if err != nil && !IsNotFound(err) {
		return TranslateError(err)
	}
	return nil
}

// JobStatus is how far a build Job has got.
type JobStatus string

const (
	JobRunning   JobStatus = "RUNNING"
	JobSucceeded JobStatus = "SUCCEEDED"
	JobFailed    JobStatus = "FAILED"
)

// PipelineJobStatus reads a build Job's outcome. A Job that has gone missing
// counts as failed: its TTL is three days, so a pipeline still marked RUNNING
// without one is not going to finish.
func PipelineJobStatus(ctx context.Context, client *Client, workNamespace, jobName string) (JobStatus, error) {
	job, err := client.Clientset.BatchV1().Jobs(workNamespace).Get(ctx, jobName, metav1.GetOptions{})
	if IsNotFound(err) {
		return JobFailed, nil
	}
	if err != nil {
		return "", TranslateError(err)
	}
	for _, condition := range job.Status.Conditions {
		if condition.Status != corev1.ConditionTrue {
			continue
		}
		switch condition.Type {
		case batchv1.JobComplete:
			return JobSucceeded, nil
		case batchv1.JobFailed:
			return JobFailed, nil
		}
	}
	// Succeeded/Failed counts move before the condition does, so they are the
	// earlier signal and worth checking too.
	if job.Status.Succeeded > 0 {
		return JobSucceeded, nil
	}
	if job.Status.Failed > 0 {
		return JobFailed, nil
	}
	return JobRunning, nil
}
