package k8s

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	applycorev1 "k8s.io/client-go/applyconfigurations/core/v1"
)

// fieldManager matches the patch identity the Java gateways use.
const fieldManager = "oops"

func CanConnect(ctx context.Context, apiServerURL, token string) bool {
	cluster, err := NewCluster(apiServerURL, token)
	if err != nil {
		return false
	}
	_, err = cluster.Clientset.Discovery().ServerVersion()
	return err == nil
}

func NamespaceExists(ctx context.Context, cluster *Cluster, namespace string) (bool, error) {
	_, err := cluster.Clientset.CoreV1().Namespaces().Get(ctx, namespace, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return false, nil
	}
	return err == nil, err
}

func CreateNamespace(ctx context.Context, cluster *Cluster, namespace string) error {
	_, err := cluster.Clientset.CoreV1().Namespaces().Create(ctx, &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{Name: namespace},
	}, metav1.CreateOptions{})
	return err
}

func applySecret(ctx context.Context, cluster *Cluster, namespace, name, secretType string, data map[string]string) error {
	apply := applycorev1.Secret(name, namespace).
		WithType(corev1.SecretType(secretType)).
		WithStringData(data)
	_, err := cluster.Clientset.CoreV1().Secrets(namespace).Apply(ctx, apply,
		metav1.ApplyOptions{FieldManager: fieldManager, Force: true})
	return err
}

// SyncImagePullSecret mirrors KubernetesEnvironmentGateway.syncImagePullSecret:
// a dockerconfigjson secret named "dockerhub" in the work namespace.
func SyncImagePullSecret(ctx context.Context, cluster *Cluster, workNamespace, registryURL, username, password string) error {
	if workNamespace == "" || registryURL == "" || username == "" || password == "" {
		return nil
	}
	auth := base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
	dockerConfig := fmt.Sprintf(
		`{"auths":{"%s":{"username":"%s","password":"%s","auth":"%s"}}}`,
		registryURL, username, password, auth)
	return applySecret(ctx, cluster, workNamespace, "dockerhub",
		"kubernetes.io/dockerconfigjson", map[string]string{".dockerconfigjson": dockerConfig})
}

// SyncGitCredentialSecret mirrors syncGitCredentialSecret: a "git-credential"
// secret carrying .netrc and/or id_rsa, deleted when the credential is empty.
func SyncGitCredentialSecret(ctx context.Context, cluster *Cluster, workNamespace, username, password, privateKey string) error {
	if workNamespace == "" {
		return nil
	}
	secrets := cluster.Clientset.CoreV1().Secrets(workNamespace)
	if username == "" && password == "" && privateKey == "" {
		err := secrets.Delete(ctx, "git-credential", metav1.DeleteOptions{})
		if apierrors.IsNotFound(err) {
			return nil
		}
		return err
	}
	data := map[string]string{}
	if username != "" || password != "" {
		var builder strings.Builder
		builder.WriteString("default")
		if username != "" {
			builder.WriteString(" login " + username)
		}
		if password != "" {
			builder.WriteString(" password " + password)
		}
		builder.WriteString("\n")
		data[".netrc"] = builder.String()
	}
	if privateKey != "" {
		if !strings.HasSuffix(privateKey, "\n") {
			privateKey += "\n"
		}
		data["id_rsa"] = privateKey
	}
	return applySecret(ctx, cluster, workNamespace, "git-credential", "Opaque", data)
}

var registryHTTPClient = &http.Client{Timeout: 5 * time.Second}

// ValidateImageRepository mirrors isImageRepositoryValid: GET the registry
// root with basic auth and accept any 2xx/3xx.
func ValidateImageRepository(repositoryURL, username, password string) bool {
	if repositoryURL == "" || username == "" || password == "" {
		return false
	}
	parsed, err := url.Parse(repositoryURL)
	if err != nil || parsed.Host == "" {
		if parsed, err = url.Parse("https://" + repositoryURL); err != nil || parsed.Host == "" {
			return false
		}
	}
	if parsed.Scheme == "" {
		parsed.Scheme = "https"
	}
	root := parsed.Scheme + "://" + parsed.Host + "/"
	request, err := http.NewRequest(http.MethodGet, root, nil)
	if err != nil {
		return false
	}
	request.SetBasicAuth(username, password)
	response, err := registryHTTPClient.Do(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	return response.StatusCode >= 200 && response.StatusCode < 400
}
