package k8s

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/domain"
)

// EnvironmentGateway is the cluster side of registering an environment: probing
// the API server, creating the work namespace, and keeping the registry and git
// credentials in it in step with what the database holds.
type EnvironmentGateway struct{ pool *Pool }

func NewEnvironmentGateway(pool *Pool) *EnvironmentGateway { return &EnvironmentGateway{pool: pool} }

// registryProbe is used only to check registry credentials, so it gets a short
// timeout of its own rather than blocking a request for the default minute.
var registryProbe = &http.Client{Timeout: 5 * time.Second}

// CanConnect reports whether the API server answers with these credentials.
func (g *EnvironmentGateway) CanConnect(ctx context.Context, apiServer *domain.KubernetesApiServer) bool {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		slog.Warn("failed to build a client for the Kubernetes API server", "url", domain.Deref(apiServer.URL), "error", err)
		return false
	}
	if _, err := client.Clientset.Discovery().ServerVersion(); err != nil {
		slog.Warn("failed to connect to the Kubernetes API server", "url", domain.Deref(apiServer.URL), "error", err)
		return false
	}
	return true
}

// NamespaceExists reports whether the work namespace is already there.
func (g *EnvironmentGateway) NamespaceExists(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace string) (bool, error) {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return false, err
	}
	_, err = client.Clientset.CoreV1().Namespaces().Get(ctx, namespace, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return false, nil
	}
	if err != nil {
		return false, TranslateError(err)
	}
	return true, nil
}

// CreateNamespace creates the work namespace; an existing one is not an error.
func (g *EnvironmentGateway) CreateNamespace(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace string) error {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return err
	}
	_, err = client.Clientset.CoreV1().Namespaces().Create(ctx,
		&corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: namespace}},
		metav1.CreateOptions{FieldManager: FieldManager})
	if err != nil && !apierrors.IsAlreadyExists(err) {
		return TranslateError(err)
	}
	return nil
}

// IsImageRepositoryValid checks the registry accepts the stored credentials, by
// asking for its root with HTTP basic auth.
func (g *EnvironmentGateway) IsImageRepositoryValid(ctx context.Context, repository *domain.ImageRepository) bool {
	if !repository.HasCredentials() {
		return false
	}
	parsed, err := url.Parse(domain.Deref(repository.URL))
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return false
	}
	root := &url.URL{Scheme: parsed.Scheme, Host: parsed.Host, Path: "/"}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, root.String(), nil)
	if err != nil {
		return false
	}
	request.SetBasicAuth(domain.Deref(repository.Username), domain.Deref(repository.Password))
	response, err := registryProbe.Do(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	return response.StatusCode >= 200 && response.StatusCode < 300
}

// SyncImagePullSecret writes the registry credentials into the work namespace as
// the `dockerhub` Secret. An environment whose registry needs no credentials
// simply has none written — every consumer treats the Secret as optional.
func (g *EnvironmentGateway) SyncImagePullSecret(ctx context.Context, environment *domain.Environment) error {
	workNamespace := domain.Deref(environment.WorkNamespace)
	repository := environment.ImageRepository
	if workNamespace == "" || environment.KubernetesApiServer == nil || !repository.HasCredentials() {
		return nil
	}
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	registry := domain.Deref(repository.URL)
	username := domain.Deref(repository.Username)
	password := domain.Deref(repository.Password)
	// Marshalled rather than formatted: a password containing a quote or a
	// backslash would otherwise produce a config Kubernetes cannot parse.
	config, err := json.Marshal(map[string]any{"auths": map[string]any{registry: map[string]string{
		"username": username,
		"password": password,
		"auth":     base64.StdEncoding.EncodeToString([]byte(username + ":" + password)),
	}}})
	if err != nil {
		return err
	}
	secret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: ImagePullSecretName, Namespace: workNamespace},
		Type:       corev1.SecretTypeDockerConfigJson,
		Data:       map[string][]byte{corev1.DockerConfigJsonKey: config},
	}
	if err := applySecret(ctx, client, workNamespace, secret); err != nil {
		return fmt.Errorf("sync dockerhub secret into %s: %w", workNamespace, err)
	}
	slog.Info("synced the dockerhub secret", "namespace", workNamespace)
	return nil
}

// SyncGitCredentialSecret writes the git credentials into the work namespace as
// the `git-credential` Secret, and deletes it when the credentials are cleared —
// otherwise a removed credential would keep working until the namespace was
// rebuilt.
func (g *EnvironmentGateway) SyncGitCredentialSecret(ctx context.Context, environment *domain.Environment) error {
	workNamespace := domain.Deref(environment.WorkNamespace)
	if workNamespace == "" || environment.KubernetesApiServer == nil {
		return nil
	}
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	credential := environment.GitCredential
	if credential.IsEmpty() {
		err := client.Clientset.CoreV1().Secrets(workNamespace).Delete(ctx, GitCredentialSecretName, metav1.DeleteOptions{})
		if err != nil && !apierrors.IsNotFound(err) {
			return fmt.Errorf("remove git-credential secret from %s: %w", workNamespace, TranslateError(err))
		}
		return nil
	}
	data := map[string][]byte{}
	if netrc := buildNetrc(credential); netrc != "" {
		data[".netrc"] = []byte(netrc)
	}
	if key := strings.TrimSpace(domain.Deref(credential.PrivateKey)); key != "" {
		// ssh refuses a key without its trailing newline.
		if !strings.HasSuffix(key, "\n") {
			key += "\n"
		}
		data["id_rsa"] = []byte(key)
	}
	secret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: GitCredentialSecretName, Namespace: workNamespace},
		Type:       corev1.SecretTypeOpaque,
		Data:       data,
	}
	if err := applySecret(ctx, client, workNamespace, secret); err != nil {
		return fmt.Errorf("sync git-credential secret into %s: %w", workNamespace, err)
	}
	slog.Info("synced the git-credential secret", "namespace", workNamespace)
	return nil
}

// buildNetrc renders the HTTPS credentials as a .netrc; "" when there are none.
func buildNetrc(credential *domain.GitCredential) string {
	username := strings.TrimSpace(domain.Deref(credential.Username))
	password := strings.TrimSpace(domain.Deref(credential.Password))
	if username == "" && password == "" {
		return ""
	}
	line := "default"
	if username != "" {
		line += " login " + username
	}
	if password != "" {
		line += " password " + password
	}
	return line + "\n"
}

// applySecret creates the Secret or replaces the one already there.
func applySecret(ctx context.Context, client *Client, namespace string, secret *corev1.Secret) error {
	secrets := client.Clientset.CoreV1().Secrets(namespace)
	_, err := secrets.Create(ctx, secret, metav1.CreateOptions{FieldManager: FieldManager})
	if apierrors.IsAlreadyExists(err) {
		_, err = secrets.Update(ctx, secret, metav1.UpdateOptions{FieldManager: FieldManager})
	}
	return TranslateError(err)
}
