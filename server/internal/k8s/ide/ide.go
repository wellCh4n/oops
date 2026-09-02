// Package ide is the Kubernetes adapter behind the code-server IDE endpoints,
// mirroring KubernetesIdeGateway: one StatefulSet per IDE with a Service and a
// Traefik IngressRoute hanging off it through ownerReferences.
package ide

import (
	"context"
	_ "embed"
	"encoding/json"
	"log/slog"
	"sort"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

const (
	LabelType  = "oops.type"
	LabelApp   = "oops.app" // note: not oops.app.name
	LabelIDEID = "oops.ide.id"

	AnnotationName = "oops.ide.name"

	TypeValue = "IDE"

	configMapName          = "ide-config"
	configKeySettings      = "settings.json"
	configKeyEnv           = ".env"
	configKeyExtensions    = "extensions"
	serverSideFieldManager = "oops"
)

//go:embed ide-default-config.json
var defaultConfigJSON []byte

// Options is the oops.ide.* block plus the two pipeline/ingress properties
// the gateway borrows (clone image, Traefik cert resolver).
type Options struct {
	Domain       string
	HTTPS        bool
	Image        string
	Middlewares  []string
	CloneImage   string
	CertResolver string
}

// Config is the IdeConfigDto: settings is a JSON document kept as a string.
type Config struct {
	Settings   string `json:"settings"`
	Env        string `json:"env"`
	Extensions string `json:"extensions"`
}

// Instance is the IdeDto.
type Instance struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Host      string `json:"host"`
	HTTPS     bool   `json:"https"`
	CreatedAt string `json:"createdAt"`
	Ready     bool   `json:"ready"`
}

// CreateRequest is the CreateIdeCommand body.
type CreateRequest struct {
	Name       string `json:"name"`
	Branch     string `json:"branch"`
	Settings   string `json:"settings"`
	Env        string `json:"env"`
	Extensions string `json:"extensions"`
}

// Gateway manages IDE StatefulSets through the shared client pool.
type Gateway struct {
	pool *k8s.Pool
	opts Options
}

// New builds a Gateway.
func New(pool *k8s.Pool, opts Options) *Gateway {
	return &Gateway{pool: pool, opts: opts}
}

// fileDefaults mirrors loadFileDefaults: settings as compact JSON, the other
// two as plain text; a corrupt file yields {"{}", "", ""} with a warning.
func fileDefaults() Config {
	var root struct {
		Settings   json.RawMessage `json:"settings"`
		Env        string          `json:"env"`
		Extensions string          `json:"extensions"`
	}
	if err := json.Unmarshal(defaultConfigJSON, &root); err != nil {
		slog.Warn("Failed to load ide-default-config.json, using empty defaults", "error", err)
		return Config{Settings: "{}"}
	}
	settings := "{}"
	if len(root.Settings) > 0 {
		var compact json.RawMessage
		if err := json.Unmarshal(root.Settings, &compact); err == nil {
			if compacted, err := json.Marshal(compact); err == nil {
				settings = string(compacted)
			}
		}
	}
	return Config{Settings: settings, Env: root.Env, Extensions: root.Extensions}
}

// configMapIsComplete reports whether the ide-config ConfigMap carries all
// three keys and can therefore be returned as-is.
func configMapIsComplete(data map[string]string) bool {
	if data == nil {
		return false
	}
	for _, key := range []string{configKeySettings, configKeyEnv, configKeyExtensions} {
		if _, ok := data[key]; !ok {
			return false
		}
	}
	return true
}

// mergeConfigMapData overlays the file defaults onto whatever the ConfigMap
// already holds.
func mergeConfigMapData(existing map[string]string, defaults Config) map[string]string {
	merged := make(map[string]string, len(existing)+3)
	for key, value := range existing {
		merged[key] = value
	}
	merged[configKeySettings] = defaults.Settings
	merged[configKeyEnv] = defaults.Env
	merged[configKeyExtensions] = defaults.Extensions
	return merged
}

// DefaultConfig returns the environment's ide-config ConfigMap when it is
// complete; otherwise it seeds the ConfigMap with the file defaults via
// server-side apply and returns the file defaults. A nil environment returns
// the file defaults without touching any cluster.
func (g *Gateway) DefaultConfig(ctx context.Context, env *domain.Environment) (Config, error) {
	defaults := fileDefaults()
	if env == nil {
		return defaults, nil
	}
	client, err := g.pool.Get(env.KubernetesApiServer)
	if err != nil {
		return Config{}, k8s.TranslateError(err)
	}
	workNamespace := domain.Deref(env.WorkNamespace)

	var existing map[string]string
	configMap, err := client.Clientset.CoreV1().ConfigMaps(workNamespace).Get(ctx, configMapName, metav1.GetOptions{})
	switch {
	case err == nil:
		if configMapIsComplete(configMap.Data) {
			return Config{
				Settings:   configMap.Data[configKeySettings],
				Env:        configMap.Data[configKeyEnv],
				Extensions: configMap.Data[configKeyExtensions],
			}, nil
		}
		existing = configMap.Data
	case k8s.IsNotFound(err):
	default:
		return Config{}, k8s.TranslateError(err)
	}

	seeded := &corev1.ConfigMap{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "ConfigMap"},
		ObjectMeta: metav1.ObjectMeta{Name: configMapName, Namespace: workNamespace},
		Data:       mergeConfigMapData(existing, defaults),
	}
	if err := serverSideApply(ctx, seeded, func(payload []byte) error {
		_, applyErr := client.Clientset.CoreV1().ConfigMaps(workNamespace).Patch(ctx, configMapName, types.ApplyPatchType, payload, applyOptions())
		return applyErr
	}); err != nil {
		return Config{}, k8s.TranslateError(err)
	}
	return defaults, nil
}

// List returns the application's IDEs in the environment, newest first. A nil
// environment yields an empty list.
func (g *Gateway) List(ctx context.Context, env *domain.Environment, app string) ([]Instance, error) {
	if env == nil {
		return []Instance{}, nil
	}
	client, err := g.pool.Get(env.KubernetesApiServer)
	if err != nil {
		return nil, k8s.TranslateError(err)
	}
	statefulSets, err := client.Clientset.AppsV1().StatefulSets(domain.Deref(env.WorkNamespace)).List(ctx, metav1.ListOptions{
		LabelSelector: LabelType + "=" + TypeValue + "," + LabelApp + "=" + app,
	})
	if err != nil {
		return nil, k8s.TranslateError(err)
	}

	items := statefulSets.Items
	sort.SliceStable(items, func(left, right int) bool {
		leftTime, rightTime := items[left].CreationTimestamp, items[right].CreationTimestamp
		switch {
		case leftTime.IsZero() && rightTime.IsZero():
			return false
		case leftTime.IsZero():
			return false // nulls last
		case rightTime.IsZero():
			return true
		default:
			return rightTime.Time.Before(leftTime.Time)
		}
	})

	instances := make([]Instance, 0, len(items))
	for index := range items {
		statefulSet := &items[index]
		id := statefulSet.Name
		name := statefulSet.Annotations[AnnotationName]
		if isBlank(name) {
			name = id
		}
		createdAt := ""
		if !statefulSet.CreationTimestamp.IsZero() {
			createdAt = statefulSet.CreationTimestamp.UTC().Format(metav1RFC3339)
		}
		instances = append(instances, Instance{
			ID:        id,
			Name:      name,
			Host:      id + "." + g.opts.Domain,
			HTTPS:     g.opts.HTTPS,
			CreatedAt: createdAt,
			Ready:     statefulSet.Status.ReadyReplicas > 0,
		})
	}
	return instances, nil
}

// metav1RFC3339 is the wire format of creationTimestamp (second precision, Z).
const metav1RFC3339 = "2006-01-02T15:04:05Z07:00"

// Delete removes the StatefulSet; the Service and IngressRoute are garbage
// collected through their ownerReference. A nil environment is a no-op.
func (g *Gateway) Delete(ctx context.Context, env *domain.Environment, name string) error {
	if env == nil {
		return nil
	}
	client, err := g.pool.Get(env.KubernetesApiServer)
	if err != nil {
		return k8s.TranslateError(err)
	}
	err = client.Clientset.AppsV1().StatefulSets(domain.Deref(env.WorkNamespace)).Delete(ctx, name, metav1.DeleteOptions{})
	if err != nil && !k8s.IsNotFound(err) {
		return k8s.TranslateError(err)
	}
	return nil
}

// serverSideApply marshals the typed object (TypeMeta must be set) and hands
// the payload to the caller's Patch, matching Fabric8's serverSideApply().
func serverSideApply(_ context.Context, object any, patch func(payload []byte) error) error {
	payload, err := json.Marshal(object)
	if err != nil {
		return err
	}
	return patch(payload)
}

func applyOptions() metav1.PatchOptions {
	return metav1.PatchOptions{FieldManager: serverSideFieldManager, Force: domain.Ptr(true)}
}
