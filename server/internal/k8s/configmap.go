package k8s

import (
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"sort"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

// ConfigMapItem is one config entry as returned by the config editor endpoint.
type ConfigMapItem struct {
	Key       string  `json:"key"`
	Value     string  `json:"value"`
	Secret    bool    `json:"secret"`
	MountPath *string `json:"mountPath"`
	Group     *string `json:"group"`
	Comment   *string `json:"comment"`
	Order     *int    `json:"order"`
	Name      string  `json:"name"`
}

// ConfigMapCommand is one incoming config entry; order is the list index.
type ConfigMapCommand struct {
	Key       string  `json:"key"`
	Value     string  `json:"value"`
	Secret    bool    `json:"secret"`
	MountPath *string `json:"mountPath"`
	Group     *string `json:"group"`
	Comment   *string `json:"comment"`
}

// ConfigMeta is the per-key UI metadata stored in the oops.config-meta
// annotation. Nulls are emitted, mirroring Jackson's default serialization.
type ConfigMeta struct {
	Group   *string `json:"group"`
	Comment *string `json:"comment"`
	Order   *int    `json:"order"`
}

// newConfigMeta mirrors ConfigMeta.of: blank strings become nil, and a meta
// with nothing set is not stored at all (nil).
func newConfigMeta(group, comment *string, order *int) *ConfigMeta {
	normalizedGroup := domain.TrimToNil(group)
	normalizedComment := domain.TrimToNil(comment)
	if normalizedGroup == nil && normalizedComment == nil && order == nil {
		return nil
	}
	return &ConfigMeta{Group: normalizedGroup, Comment: normalizedComment, Order: order}
}

// ConfigMapGateway reads and rewrites an application's config resources.
type ConfigMapGateway struct{ pool *Pool }

func NewConfigMapGateway(pool *Pool) *ConfigMapGateway { return &ConfigMapGateway{pool: pool} }

// GetConfigMaps reads the four config resources and returns their items
// sorted by order (nil last) then key.
func (g *ConfigMapGateway) GetConfigMaps(ctx context.Context, environment *domain.Environment, namespace, applicationName string) ([]ConfigMapItem, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	items, err := getConfigMaps(ctx, client.Clientset, namespace, applicationName)
	return items, TranslateError(err)
}

func getConfigMaps(ctx context.Context, clientset kubernetes.Interface, namespace, applicationName string) ([]ConfigMapItem, error) {
	filesName := applicationName + FilesResourceSuffix
	items := []ConfigMapItem{}

	envConfigMap, err := getConfigMapOrNil(ctx, clientset, namespace, applicationName)
	if err != nil {
		return nil, err
	}
	if envConfigMap != nil {
		metas := readConfigMetas(envConfigMap.Annotations)
		for _, key := range sortedKeys(envConfigMap.Data) {
			items = append(items, buildConfigItem(key, envConfigMap.Data[key], false, nil, metas[key]))
		}
	}
	envSecret, err := getSecretOrNil(ctx, clientset, namespace, applicationName)
	if err != nil {
		return nil, err
	}
	if envSecret != nil {
		metas := readConfigMetas(envSecret.Annotations)
		for _, key := range sortedKeys(envSecret.Data) {
			items = append(items, buildConfigItem(key, string(envSecret.Data[key]), true, nil, metas[key]))
		}
	}
	fileConfigMap, err := getConfigMapOrNil(ctx, clientset, namespace, filesName)
	if err != nil {
		return nil, err
	}
	if fileConfigMap != nil {
		mounts := readMounts(fileConfigMap.Annotations)
		metas := readConfigMetas(fileConfigMap.Annotations)
		for _, key := range sortedKeys(fileConfigMap.Data) {
			items = append(items, buildConfigItem(key, fileConfigMap.Data[key], false, mountPointer(mounts, key), metas[key]))
		}
	}
	fileSecret, err := getSecretOrNil(ctx, clientset, namespace, filesName)
	if err != nil {
		return nil, err
	}
	if fileSecret != nil {
		mounts := readMounts(fileSecret.Annotations)
		metas := readConfigMetas(fileSecret.Annotations)
		for _, key := range sortedKeys(fileSecret.Data) {
			items = append(items, buildConfigItem(key, string(fileSecret.Data[key]), true, mountPointer(mounts, key), metas[key]))
		}
	}
	SortConfigMapItems(items)
	return items, nil
}

// SortConfigMapItems orders by order (nil -> MaxInt) then key.
func SortConfigMapItems(items []ConfigMapItem) {
	orderOf := func(item ConfigMapItem) int {
		if item.Order == nil {
			return math.MaxInt32
		}
		return *item.Order
	}
	sort.SliceStable(items, func(i, j int) bool {
		left, right := orderOf(items[i]), orderOf(items[j])
		if left != right {
			return left < right
		}
		return items[i].Key < items[j].Key
	})
}

func buildConfigItem(key, value string, secret bool, mountPath *string, meta *ConfigMeta) ConfigMapItem {
	item := ConfigMapItem{Key: key, Value: value, Secret: secret, MountPath: mountPath, Name: domain.ToResourceName(key)}
	if meta != nil {
		item.Group = meta.Group
		item.Comment = meta.Comment
		item.Order = meta.Order
	}
	return item
}

func mountPointer(mounts map[string]string, key string) *string {
	if mountPath, ok := mounts[key]; ok {
		return domain.Ptr(mountPath)
	}
	return nil
}

// UpdateConfigMaps rewrites the four config resources from the incoming list.
func (g *ConfigMapGateway) UpdateConfigMaps(ctx context.Context, environment *domain.Environment, namespace, applicationName string, commands []ConfigMapCommand) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	return TranslateError(updateConfigMaps(ctx, client, namespace, applicationName, commands))
}

// configPartition is the four-way split of the incoming items.
type configPartition struct {
	envConfig, envSecret, fileConfig, fileSecret                     map[string]string
	fileConfigMounts, fileSecretMounts                               map[string]string
	envConfigMetas, envSecretMetas, fileConfigMetas, fileSecretMetas map[string]*ConfigMeta
}

// PartitionConfigCommands mirrors the bucketing in updateConfigMap; the list
// index becomes each item's order.
func PartitionConfigCommands(commands []ConfigMapCommand) configPartition {
	partition := configPartition{
		envConfig: map[string]string{}, envSecret: map[string]string{}, fileConfig: map[string]string{}, fileSecret: map[string]string{},
		fileConfigMounts: map[string]string{}, fileSecretMounts: map[string]string{},
		envConfigMetas: map[string]*ConfigMeta{}, envSecretMetas: map[string]*ConfigMeta{}, fileConfigMetas: map[string]*ConfigMeta{}, fileSecretMetas: map[string]*ConfigMeta{},
	}
	for position, command := range commands {
		order := position
		meta := newConfigMeta(command.Group, command.Comment, &order)
		mounted := !blankPtr(command.MountPath)
		switch {
		case mounted && command.Secret:
			partition.fileSecret[command.Key] = command.Value
			partition.fileSecretMounts[command.Key] = strings.TrimSpace(*command.MountPath)
			putMeta(partition.fileSecretMetas, command.Key, meta)
		case mounted:
			partition.fileConfig[command.Key] = command.Value
			partition.fileConfigMounts[command.Key] = strings.TrimSpace(*command.MountPath)
			putMeta(partition.fileConfigMetas, command.Key, meta)
		case command.Secret:
			partition.envSecret[command.Key] = command.Value
			putMeta(partition.envSecretMetas, command.Key, meta)
		default:
			partition.envConfig[command.Key] = command.Value
			putMeta(partition.envConfigMetas, command.Key, meta)
		}
	}
	return partition
}

func putMeta(metas map[string]*ConfigMeta, key string, meta *ConfigMeta) {
	if meta != nil {
		metas[key] = meta
	}
}

func updateConfigMaps(ctx context.Context, client *Client, namespace, applicationName string, commands []ConfigMapCommand) error {
	if err := ensureNamespace(ctx, client, namespace, false); err != nil {
		return err
	}
	ownerRef, err := findStatefulSetOwnerReference(ctx, client.Clientset, namespace, applicationName)
	if err != nil {
		return err
	}
	partition := PartitionConfigCommands(commands)
	filesName := applicationName + FilesResourceSuffix

	if err := applyConfigMap(ctx, client.Clientset, namespace, applicationName, partition.envConfig, map[string]string{}, partition.envConfigMetas, ownerRef); err != nil {
		return err
	}
	if err := applyOrDeleteSecret(ctx, client.Clientset, namespace, applicationName, partition.envSecret, map[string]string{}, partition.envSecretMetas, ownerRef); err != nil {
		return err
	}
	if err := applyOrDeleteConfigMap(ctx, client.Clientset, namespace, filesName, partition.fileConfig, partition.fileConfigMounts, partition.fileConfigMetas, ownerRef); err != nil {
		return err
	}
	return applyOrDeleteSecret(ctx, client.Clientset, namespace, filesName, partition.fileSecret, partition.fileSecretMounts, partition.fileSecretMetas, ownerRef)
}

func applyOrDeleteConfigMap(ctx context.Context, clientset kubernetes.Interface, namespace, name string, data, mounts map[string]string, metas map[string]*ConfigMeta, ownerRef *metav1.OwnerReference) error {
	if len(data) == 0 {
		err := clientset.CoreV1().ConfigMaps(namespace).Delete(ctx, name, metav1.DeleteOptions{})
		if err != nil && !IsNotFound(err) {
			return err
		}
		return nil
	}
	return applyConfigMap(ctx, clientset, namespace, name, data, mounts, metas, ownerRef)
}

func applyConfigMap(ctx context.Context, clientset kubernetes.Interface, namespace, name string, data, mounts map[string]string, metas map[string]*ConfigMeta, ownerRef *metav1.OwnerReference) error {
	configMap := &corev1.ConfigMap{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "ConfigMap"},
		ObjectMeta: configObjectMeta(namespace, name, mounts, metas, ownerRef),
		Data:       data,
	}
	patch, err := applyPatch(configMap)
	if err != nil {
		return err
	}
	_, err = clientset.CoreV1().ConfigMaps(namespace).Patch(ctx, name, applyPatchType, patch, forceApply)
	return err
}

func applyOrDeleteSecret(ctx context.Context, clientset kubernetes.Interface, namespace, name string, data, mounts map[string]string, metas map[string]*ConfigMeta, ownerRef *metav1.OwnerReference) error {
	if len(data) == 0 {
		err := clientset.CoreV1().Secrets(namespace).Delete(ctx, name, metav1.DeleteOptions{})
		if err != nil && !IsNotFound(err) {
			return err
		}
		return nil
	}
	secret := &corev1.Secret{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: configObjectMeta(namespace, name, mounts, metas, ownerRef),
		Type:       corev1.SecretTypeOpaque,
		StringData: data,
	}
	patch, err := applyPatch(secret)
	if err != nil {
		return err
	}
	_, err = clientset.CoreV1().Secrets(namespace).Patch(ctx, name, applyPatchType, patch, forceApply)
	return err
}

func configObjectMeta(namespace, name string, mounts map[string]string, metas map[string]*ConfigMeta, ownerRef *metav1.OwnerReference) metav1.ObjectMeta {
	meta := metav1.ObjectMeta{
		Name:        name,
		Namespace:   namespace,
		Annotations: ConfigAnnotations(mounts, metas),
	}
	if ownerRef != nil {
		meta.OwnerReferences = []metav1.OwnerReference{*ownerRef}
	}
	return meta
}

// ConfigAnnotations serializes the mounts and config metas. Empty maps are
// omitted; with nothing to write the result is an empty (non-nil) map so SSA
// clears any previously managed annotations.
func ConfigAnnotations(mounts map[string]string, metas map[string]*ConfigMeta) map[string]string {
	annotations := map[string]string{}
	if len(mounts) > 0 {
		if encoded, err := json.Marshal(mounts); err == nil {
			annotations[MountAnnotation] = string(encoded)
		} else {
			slog.Warn("Failed to serialize mount annotation", "error", err.Error())
		}
	}
	if len(metas) > 0 {
		if encoded, err := json.Marshal(metas); err == nil {
			annotations[ConfigMetaAnnotation] = string(encoded)
		} else {
			slog.Warn("Failed to serialize config meta annotation", "error", err.Error())
		}
	}
	return annotations
}

// readMounts parses the oops.mounts annotation (key -> mountPath).
func readMounts(annotations map[string]string) map[string]string {
	raw := annotations[MountAnnotation]
	if isBlank(raw) {
		return map[string]string{}
	}
	mounts := map[string]string{}
	if err := json.Unmarshal([]byte(raw), &mounts); err != nil {
		slog.Warn("Failed to parse mount annotation", "error", err.Error())
		return map[string]string{}
	}
	return mounts
}

// readConfigMetas parses the oops.config-meta annotation; unknown fields are ignored.
func readConfigMetas(annotations map[string]string) map[string]*ConfigMeta {
	raw := annotations[ConfigMetaAnnotation]
	if isBlank(raw) {
		return map[string]*ConfigMeta{}
	}
	metas := map[string]*ConfigMeta{}
	if err := json.Unmarshal([]byte(raw), &metas); err != nil {
		slog.Warn("Failed to parse config meta annotation", "error", err.Error())
		return map[string]*ConfigMeta{}
	}
	return metas
}

func getConfigMapOrNil(ctx context.Context, clientset kubernetes.Interface, namespace, name string) (*corev1.ConfigMap, error) {
	configMap, err := clientset.CoreV1().ConfigMaps(namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	return configMap, nil
}

func getSecretOrNil(ctx context.Context, clientset kubernetes.Interface, namespace, name string) (*corev1.Secret, error) {
	secret, err := clientset.CoreV1().Secrets(namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	return secret, nil
}

// sortedKeys returns the map keys in sorted order for deterministic output.
func sortedKeys[V any](values map[string]V) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
