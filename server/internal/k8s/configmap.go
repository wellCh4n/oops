package k8s

import (
	"context"
	"encoding/json"
	"regexp"
	"sort"
	"strings"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	applycorev1 "k8s.io/client-go/applyconfigurations/core/v1"
	applymetav1 "k8s.io/client-go/applyconfigurations/meta/v1"
)

// Annotation contract shared with the Java gateway and StatefulSetProcessor.
const (
	mountAnnotation      = "oops.mounts"
	configMetaAnnotation = "oops.config-meta"
	filesResourceSuffix  = ".files"
)

type configMeta struct {
	Group   *string `json:"group"`
	Comment *string `json:"comment"`
	Order   *int    `json:"order"`
}

// ConfigMapItem mirrors the Java DTO; Name is the derived DNS-1123 label.
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

var nonLabelCharacters = regexp.MustCompile(`[^a-z0-9]+`)

// ResourceNameOf mirrors ConfigMapItem.toResourceName.
func ResourceNameOf(key string) string {
	sanitized := nonLabelCharacters.ReplaceAllString(strings.ToLower(key), "-")
	sanitized = strings.Trim(sanitized, "-")
	if len(sanitized) > 50 {
		sanitized = strings.TrimRight(sanitized[:50], "-")
	}
	if sanitized == "" {
		return "item"
	}
	return sanitized
}

// UpdateConfigMapCommand mirrors the Java command.
type UpdateConfigMapCommand struct {
	Key       string  `json:"key"`
	Value     string  `json:"value"`
	Secret    bool    `json:"secret"`
	MountPath *string `json:"mountPath"`
	Group     *string `json:"group"`
	Comment   *string `json:"comment"`
}

func readAnnotationMap[T any](annotations map[string]string, name string) map[string]T {
	parsed := map[string]T{}
	if raw, found := annotations[name]; found && raw != "" {
		_ = json.Unmarshal([]byte(raw), &parsed)
	}
	return parsed
}

func buildConfigItem(key, value string, secret bool, mountPath *string, meta *configMeta) ConfigMapItem {
	item := ConfigMapItem{Key: key, Value: value, Secret: secret, MountPath: mountPath, Name: ResourceNameOf(key)}
	if meta != nil {
		item.Group, item.Comment, item.Order = meta.Group, meta.Comment, meta.Order
	}
	return item
}

func GetConfigMaps(ctx context.Context, cluster *Cluster, namespace, applicationName string) ([]ConfigMapItem, error) {
	client := cluster.Clientset.CoreV1()
	filesName := applicationName + filesResourceSuffix
	items := []ConfigMapItem{}

	appendFrom := func(annotations map[string]string, data map[string]string, secret bool, withMounts bool) {
		metas := readAnnotationMap[configMeta](annotations, configMetaAnnotation)
		mounts := map[string]string{}
		if withMounts {
			mounts = readAnnotationMap[string](annotations, mountAnnotation)
		}
		for key, value := range data {
			var mountPath *string
			if mounted, found := mounts[key]; found {
				mountPath = &mounted
			}
			var meta *configMeta
			if found, has := metas[key]; has {
				meta = &found
			}
			items = append(items, buildConfigItem(key, value, secret, mountPath, meta))
		}
	}

	if configMap, err := client.ConfigMaps(namespace).Get(ctx, applicationName, metav1.GetOptions{}); err == nil {
		appendFrom(configMap.Annotations, configMap.Data, false, false)
	} else if !apierrors.IsNotFound(err) {
		return nil, err
	}
	if secret, err := client.Secrets(namespace).Get(ctx, applicationName, metav1.GetOptions{}); err == nil {
		appendFrom(secret.Annotations, decodeSecretData(secret), true, false)
	} else if !apierrors.IsNotFound(err) {
		return nil, err
	}
	if configMap, err := client.ConfigMaps(namespace).Get(ctx, filesName, metav1.GetOptions{}); err == nil {
		appendFrom(configMap.Annotations, configMap.Data, false, true)
	} else if !apierrors.IsNotFound(err) {
		return nil, err
	}
	if secret, err := client.Secrets(namespace).Get(ctx, filesName, metav1.GetOptions{}); err == nil {
		appendFrom(secret.Annotations, decodeSecretData(secret), true, true)
	} else if !apierrors.IsNotFound(err) {
		return nil, err
	}

	sort.SliceStable(items, func(i, j int) bool {
		orderOf := func(item ConfigMapItem) int {
			if item.Order == nil {
				return int(^uint(0) >> 1)
			}
			return *item.Order
		}
		if orderOf(items[i]) != orderOf(items[j]) {
			return orderOf(items[i]) < orderOf(items[j])
		}
		return items[i].Key < items[j].Key
	})
	return items, nil
}

func decodeSecretData(secret *corev1.Secret) map[string]string {
	decoded := map[string]string{}
	for key, value := range secret.Data {
		decoded[key] = string(value)
	}
	return decoded
}

func annotationValues(mounts map[string]string, metas map[string]configMeta) map[string]string {
	annotations := map[string]string{}
	if len(mounts) > 0 {
		if encoded, err := json.Marshal(mounts); err == nil {
			annotations[mountAnnotation] = string(encoded)
		}
	}
	if len(metas) > 0 {
		if encoded, err := json.Marshal(metas); err == nil {
			annotations[configMetaAnnotation] = string(encoded)
		}
	}
	return annotations
}

func UpdateConfigMaps(ctx context.Context, cluster *Cluster, namespace, applicationName string, commands []UpdateConfigMapCommand) error {
	client := cluster.Clientset.CoreV1()

	// Ensure the namespace exists (server-side apply like the Java gateway).
	namespaceApply := applycorev1.Namespace(namespace)
	if _, err := cluster.Clientset.CoreV1().Namespaces().Apply(ctx, namespaceApply,
		metav1.ApplyOptions{FieldManager: fieldManager, Force: true}); err != nil {
		return err
	}

	var owner *applymetav1.OwnerReferenceApplyConfiguration
	if statefulSet, err := cluster.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{}); err == nil {
		controller, block := true, true
		owner = &applymetav1.OwnerReferenceApplyConfiguration{}
		owner = owner.WithAPIVersion("apps/v1").WithKind("StatefulSet").
			WithName(applicationName).WithUID(statefulSet.UID).
			WithController(controller).WithBlockOwnerDeletion(block)
	}

	envConfig, envSecret := map[string]string{}, map[string]string{}
	fileConfig, fileSecret := map[string]string{}, map[string]string{}
	fileConfigMounts, fileSecretMounts := map[string]string{}, map[string]string{}
	envConfigMetas, envSecretMetas := map[string]configMeta{}, map[string]configMeta{}
	fileConfigMetas, fileSecretMetas := map[string]configMeta{}, map[string]configMeta{}

	for position, command := range commands {
		order := position
		meta := configMeta{Group: command.Group, Comment: command.Comment, Order: &order}
		mounted := command.MountPath != nil && strings.TrimSpace(*command.MountPath) != ""
		switch {
		case command.Secret && mounted:
			fileSecret[command.Key] = command.Value
			fileSecretMounts[command.Key] = strings.TrimSpace(*command.MountPath)
			fileSecretMetas[command.Key] = meta
		case command.Secret:
			envSecret[command.Key] = command.Value
			envSecretMetas[command.Key] = meta
		case mounted:
			fileConfig[command.Key] = command.Value
			fileConfigMounts[command.Key] = strings.TrimSpace(*command.MountPath)
			fileConfigMetas[command.Key] = meta
		default:
			envConfig[command.Key] = command.Value
			envConfigMetas[command.Key] = meta
		}
	}

	filesName := applicationName + filesResourceSuffix

	applyConfigMap := func(name string, data map[string]string, mounts map[string]string, metas map[string]configMeta) error {
		apply := applycorev1.ConfigMap(name, namespace).
			WithAnnotations(annotationValues(mounts, metas)).
			WithData(data)
		if owner != nil {
			apply = apply.WithOwnerReferences(owner)
		}
		_, err := client.ConfigMaps(namespace).Apply(ctx, apply,
			metav1.ApplyOptions{FieldManager: fieldManager, Force: true})
		return err
	}
	applySecretResource := func(name string, data map[string]string, mounts map[string]string, metas map[string]configMeta) error {
		apply := applycorev1.Secret(name, namespace).
			WithType(corev1.SecretTypeOpaque).
			WithAnnotations(annotationValues(mounts, metas)).
			WithStringData(data)
		if owner != nil {
			apply = apply.WithOwnerReferences(owner)
		}
		_, err := client.Secrets(namespace).Apply(ctx, apply,
			metav1.ApplyOptions{FieldManager: fieldManager, Force: true})
		return err
	}
	deleteIgnoringMissing := func(err error) error {
		if apierrors.IsNotFound(err) {
			return nil
		}
		return err
	}

	// The env ConfigMap is always present (envFrom references it); the others
	// exist on demand and are deleted once empty.
	if err := applyConfigMap(applicationName, envConfig, nil, envConfigMetas); err != nil {
		return err
	}
	if len(envSecret) == 0 {
		if err := deleteIgnoringMissing(client.Secrets(namespace).Delete(ctx, applicationName, metav1.DeleteOptions{})); err != nil {
			return err
		}
	} else if err := applySecretResource(applicationName, envSecret, nil, envSecretMetas); err != nil {
		return err
	}
	if len(fileConfig) == 0 {
		if err := deleteIgnoringMissing(client.ConfigMaps(namespace).Delete(ctx, filesName, metav1.DeleteOptions{})); err != nil {
			return err
		}
	} else if err := applyConfigMap(filesName, fileConfig, fileConfigMounts, fileConfigMetas); err != nil {
		return err
	}
	if len(fileSecret) == 0 {
		if err := deleteIgnoringMissing(client.Secrets(namespace).Delete(ctx, filesName, metav1.DeleteOptions{})); err != nil {
			return err
		}
	} else if err := applySecretResource(filesName, fileSecret, fileSecretMounts, fileSecretMetas); err != nil {
		return err
	}
	return nil
}
