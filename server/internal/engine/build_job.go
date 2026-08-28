package engine

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

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// ImageConfig mirrors PipelineImageProperties.
type ImageConfig struct {
	Clone           string
	Zip             string
	Push            string
	RegistryMirrors map[string]string
	UnzipExcludes   []string
}

// buildJobInput bundles what PipelineExecuteTask needed.
type buildJobInput struct {
	PipelineID      string
	PipelineName    string
	Namespace       string
	ApplicationName string
	WorkNamespace   string
	RepositoryHost  string // registry with scheme stripped
	BuildImage      string
	BuildCommand    string
	DockerFile      *store.DockerFileConfig
	Git             *store.GitPublishConfig
	Zip             *store.ZipPublishConfig
	Images          ImageConfig
}

func (input *buildJobInput) artifact() string {
	return input.RepositoryHost + "/" + input.ApplicationName + ":" + input.PipelineID
}

var registryLocationPattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._:-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)*$`)

// buildRegistriesConf converts the mirror map to Buildah registries.conf TOML,
// matching PublishContainer.buildRegistriesConf.
func buildRegistriesConf(registryMirrors map[string]string) string {
	var builder strings.Builder
	builder.WriteString("unqualified-search-registries = [\"docker.io\"]\n\n")
	prefixes := make([]string, 0, len(registryMirrors))
	for prefix := range registryMirrors {
		prefixes = append(prefixes, prefix)
	}
	sort.Strings(prefixes)
	for _, rawPrefix := range prefixes {
		prefix := strings.TrimSpace(rawPrefix)
		mirror := strings.TrimSpace(registryMirrors[rawPrefix])
		if prefix == "" || mirror == "" {
			continue
		}
		if prefix == "index.docker.io" {
			prefix = "docker.io"
		}
		if !registryLocationPattern.MatchString(prefix) || !registryLocationPattern.MatchString(mirror) {
			continue
		}
		fmt.Fprintf(&builder, "[[registry]]\nprefix = %q\nlocation = %q\n\n[[registry.mirror]]\nlocation = %q\n\n",
			prefix, prefix, mirror)
	}
	return builder.String()
}

func gitCloneCommand(git *store.GitPublishConfig) (string, error) {
	if git == nil || git.Repository == "" {
		return "", fmt.Errorf("repository URL must not be empty")
	}
	arguments := []string{"git", "clone", "--progress", "--depth", "1"}
	if git.Branch != "" {
		arguments = append(arguments, "-b", git.Branch)
	}
	arguments = append(arguments, git.Repository, "/workspace")
	return strings.Join(arguments, " "), nil
}

func zipCloneCommand(downloadURL string, unzipExcludes []string) string {
	quoted := make([]string, 0, len(unzipExcludes))
	for _, exclude := range unzipExcludes {
		quoted = append(quoted, "'"+exclude+"'")
	}
	return fmt.Sprintf(`set -e
rm -rf /workspace/* /tmp/source-download /tmp/source.zip
mkdir -p /workspace /tmp/source-download

curl -fL --connect-timeout 30 --max-time 120 '%s' -o /tmp/source.zip

if [ ! -s /tmp/source.zip ]; then
    echo "Downloaded file is empty" >&2
    exit 1
fi

magic=$(od -A n -t x1 -N 2 /tmp/source.zip 2>/dev/null | sed 's/ //g')
if [ "$magic" != "504b" ]; then
    echo "Downloaded file is not a valid ZIP archive" >&2
    exit 1
fi

unzip -o /tmp/source.zip -d /tmp/source-download -x %s

find /tmp/source-download -mindepth 1 -maxdepth 1 \
  ! -name '__MACOSX' \
  ! -name '.DS_Store' \
  > /tmp/source-entries
first_entry="$(head -n 1 /tmp/source-entries)"
entry_count="$(wc -l < /tmp/source-entries | tr -d ' ')"

if [ "$entry_count" = "1" ] && [ -d "$first_entry" ]; then
  cp -a "$first_entry"/. /workspace/
else
  cp -a /tmp/source-download/. /workspace/
  rm -rf /workspace/__MACOSX /workspace/.DS_Store
fi
`, downloadURL, strings.Join(quoted, " "))
}

const publishScript = `printf '%s' "$3" | base64 -d > /tmp/registries.conf
buildah bud --storage-driver=vfs --tls-verify=false --isolation chroot --registries-conf /tmp/registries.conf -t "$1" -f "$2" /workspace
buildah push --storage-driver=vfs --tls-verify=false --registries-conf /tmp/registries.conf "$1"
`

var workspaceMount = corev1.VolumeMount{Name: "workspace", MountPath: "/workspace"}

func secretMounts() []corev1.VolumeMount {
	return []corev1.VolumeMount{
		{Name: "registry-secret", MountPath: "/var/buildah/.docker"},
		{Name: "git-secret", MountPath: "/root/.netrc", SubPath: ".netrc"},
		{Name: "git-secret", MountPath: "/root/.ssh/id_rsa", SubPath: "id_rsa"},
	}
}

// buildPipelineJob assembles the batch Job like PipelineBuildPod + the
// container classes, returning the job and the artifact reference.
func buildPipelineJob(input *buildJobInput) (*batchv1.Job, string, error) {
	initContainers := []corev1.Container{}

	// fetch (clone) step
	var cloneCommand, cloneImage string
	if input.Zip != nil {
		if input.Images.Zip == "" {
			return nil, "", fmt.Errorf("ZIP source requires oops.pipeline.image.zip to be configured")
		}
		downloadURL := ""
		if input.Zip.URL != nil {
			downloadURL = *input.Zip.URL
		}
		if downloadURL == "" {
			return nil, "", fmt.Errorf("zip source must have a download URL")
		}
		cloneImage = input.Images.Zip
		cloneCommand = zipCloneCommand(downloadURL, input.Images.UnzipExcludes)
	} else {
		command, err := gitCloneCommand(input.Git)
		if err != nil {
			return nil, "", err
		}
		cloneImage = input.Images.Clone
		cloneCommand = command
	}
	fetch := corev1.Container{
		Name:    "fetch",
		Image:   cloneImage,
		Command: []string{"sh", "-c", cloneCommand},
		Env: []corev1.EnvVar{{
			Name:  "GIT_SSH_COMMAND",
			Value: "ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR",
		}},
		VolumeMounts: append([]corev1.VolumeMount{workspaceMount}, secretMounts()...),
	}
	initContainers = append(initContainers, fetch)

	// dockerfile step (inline USER content)
	if input.DockerFile != nil && input.DockerFile.Type != nil && *input.DockerFile.Type == "USER" &&
		input.DockerFile.Content != nil {
		encoded := base64.StdEncoding.EncodeToString([]byte(*input.DockerFile.Content))
		command := "echo 'Writing custom Dockerfile' && printf '%s' " + encoded +
			" | base64 -d > /workspace/Dockerfile && echo 'Custom Dockerfile written' && wc -c /workspace/Dockerfile"
		initContainers = append(initContainers, corev1.Container{
			Name:         "dockerfile",
			Image:        input.Images.Clone,
			WorkingDir:   "/workspace",
			Command:      []string{"sh", "-c", command},
			VolumeMounts: []corev1.VolumeMount{workspaceMount},
		})
	}

	// compile step
	if input.BuildImage != "" && input.BuildCommand != "" {
		initContainers = append(initContainers, corev1.Container{
			Name:         "compile",
			Image:        input.BuildImage,
			WorkingDir:   "/workspace",
			Command:      []string{"sh", "-c", input.BuildCommand},
			VolumeMounts: []corev1.VolumeMount{workspaceMount},
		})
	}

	// publish step
	artifact := input.artifact()
	dockerFile := "Dockerfile"
	if input.DockerFile != nil && input.DockerFile.Type != nil && *input.DockerFile.Type != "USER" &&
		input.DockerFile.Path != nil && *input.DockerFile.Path != "" {
		dockerFile = *input.DockerFile.Path
	}
	registriesConf := base64.StdEncoding.EncodeToString([]byte(buildRegistriesConf(input.Images.RegistryMirrors)))
	privileged := true
	publish := corev1.Container{
		Name:       "publish",
		Image:      input.Images.Push,
		WorkingDir: "/workspace",
		Command:    []string{"sh", "-eu", "-c", publishScript, "publish", artifact, dockerFile, registriesConf},
		Env: []corev1.EnvVar{{
			Name:  "REGISTRY_AUTH_FILE",
			Value: "/var/buildah/.docker/config.json",
		}},
		SecurityContext: &corev1.SecurityContext{Privileged: &privileged},
		VolumeMounts:    append([]corev1.VolumeMount{workspaceMount}, secretMounts()...),
	}
	initContainers = append(initContainers, publish)

	done := corev1.Container{
		Name:         "done",
		Image:        "busybox:1.36.1",
		Command:      []string{"sh", "-c", "echo done!"},
		VolumeMounts: append([]corev1.VolumeMount{workspaceMount}, secretMounts()...),
	}

	labels := map[string]string{
		"oops.type":                      "PIPELINE",
		"oops.pipeline.id":               input.PipelineID,
		"oops.pipeline.name":             input.PipelineName,
		"oops.pipeline.application.name": input.ApplicationName,
	}

	secretMode := int32(0600)
	optionalSecret := true
	backoffLimit := int32(0)
	activeDeadline := int64(2 * 60 * 60)
	ttlAfterFinished := int32(3 * 24 * 60 * 60)
	maxSkew := int32(1)

	job := &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      input.PipelineName,
			Namespace: input.WorkNamespace,
			Labels:    labels,
		},
		Spec: batchv1.JobSpec{
			BackoffLimit:            &backoffLimit,
			ActiveDeadlineSeconds:   &activeDeadline,
			TTLSecondsAfterFinished: &ttlAfterFinished,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					RestartPolicy:  corev1.RestartPolicyNever,
					InitContainers: initContainers,
					Containers:     []corev1.Container{done},
					TopologySpreadConstraints: []corev1.TopologySpreadConstraint{{
						MaxSkew:           maxSkew,
						TopologyKey:       "kubernetes.io/hostname",
						WhenUnsatisfiable: corev1.ScheduleAnyway,
						LabelSelector: &metav1.LabelSelector{
							MatchLabels: map[string]string{"oops.type": "PIPELINE"},
						},
					}},
					Volumes: []corev1.Volume{
						{Name: "workspace", VolumeSource: corev1.VolumeSource{
							EmptyDir: &corev1.EmptyDirVolumeSource{},
						}},
						{Name: "registry-secret", VolumeSource: corev1.VolumeSource{
							Secret: &corev1.SecretVolumeSource{
								SecretName: "dockerhub",
								Items:      []corev1.KeyToPath{{Key: ".dockerconfigjson", Path: "config.json"}},
							},
						}},
						{Name: "git-secret", VolumeSource: corev1.VolumeSource{
							Secret: &corev1.SecretVolumeSource{
								SecretName:  "git-credential",
								Optional:    &optionalSecret,
								DefaultMode: &secretMode,
								Items: []corev1.KeyToPath{
									{Key: ".netrc", Path: ".netrc"},
									{Key: "id_rsa", Path: "id_rsa"},
								},
							},
						}},
					},
				},
			},
		},
	}
	return job, artifact, nil
}

// SubmitBuild creates the build Job in the work namespace.
func SubmitBuild(ctx context.Context, cluster *k8s.Cluster, input *buildJobInput) (string, error) {
	job, artifact, err := buildPipelineJob(input)
	if err != nil {
		return "", err
	}
	if _, err := cluster.Clientset.BatchV1().Jobs(input.WorkNamespace).Create(ctx, job, metav1.CreateOptions{}); err != nil {
		return "", fmt.Errorf("failed to create pipeline job %s: %w", input.PipelineName, err)
	}
	return artifact, nil
}
