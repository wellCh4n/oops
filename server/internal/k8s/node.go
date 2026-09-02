package k8s

import (
	"context"
	"fmt"
	"math/big"
	"sort"
	"strings"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"

	"github.com/wellch4n/oops/server/internal/domain"
)

// HostnameLabel is what node affinity matches on.
const HostnameLabel = "kubernetes.io/hostname"

// nodeRoleLabelPrefix marks a node's role; the suffix is the role name, and an
// empty suffix is the control plane's historical "master".
const nodeRoleLabelPrefix = "node-role.kubernetes.io/"

// unknownValue is what the UI shows for a field the node does not report.
const unknownValue = "-"

// NodeStatusView is one row of the node list.
type NodeStatusView struct {
	Name                    string  `json:"name"`
	Hostname                string  `json:"hostname"`
	Ready                   bool    `json:"ready"`
	Schedulable             bool    `json:"schedulable"`
	Roles                   string  `json:"roles"`
	InternalIP              string  `json:"internalIP"`
	KubeletVersion          string  `json:"kubeletVersion"`
	OSImage                 string  `json:"osImage"`
	ContainerRuntimeVersion string  `json:"containerRuntimeVersion"`
	CPU                     string  `json:"cpu"`
	Memory                  string  `json:"memory"`
	Pods                    string  `json:"pods"`
	CreationTimestamp       *string `json:"creationTimestamp"`
}

// ListNodes returns every node of one environment, ordered by name.
func ListNodes(ctx context.Context, pool *Pool, environment *domain.Environment) ([]NodeStatusView, error) {
	client, err := pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	list, err := client.Clientset.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, TranslateError(err)
	}
	views := make([]NodeStatusView, 0, len(list.Items))
	for index := range list.Items {
		views = append(views, nodeStatusViewOf(&list.Items[index]))
	}
	sort.Slice(views, func(i, j int) bool {
		return strings.ToLower(views[i].Name) < strings.ToLower(views[j].Name)
	})
	return views, nil
}

// SetNodeSchedulable cordons or uncordons a node.
//
// It patches rather than reading the node and writing it back: the kubelet
// updates node status every few seconds, so a read-modify-write loses the race
// often enough to matter and fails with a 409 the operator can do nothing about.
// A patch touches only the field being changed.
func SetNodeSchedulable(ctx context.Context, pool *Pool, environment *domain.Environment, nodeName string, schedulable bool) error {
	client, err := pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	patch := fmt.Sprintf(`{"spec":{"unschedulable":%t}}`, !schedulable)
	_, err = client.Clientset.CoreV1().Nodes().Patch(ctx, nodeName,
		types.StrategicMergePatchType, []byte(patch), metav1.PatchOptions{FieldManager: FieldManager})
	return TranslateError(err)
}

// ListServiceAccounts names the ServiceAccounts an application may run as.
func ListServiceAccounts(ctx context.Context, pool *Pool, environment *domain.Environment, namespace string) ([]string, error) {
	client, err := pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	list, err := client.Clientset.CoreV1().ServiceAccounts(namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, TranslateError(err)
	}
	names := make([]string, 0, len(list.Items))
	for _, account := range list.Items {
		names = append(names, account.Name)
	}
	sort.Strings(names)
	return names, nil
}

func nodeStatusViewOf(node *corev1.Node) NodeStatusView {
	view := NodeStatusView{
		Name:                    node.Name,
		Hostname:                nodeHostname(node),
		Ready:                   nodeReady(node),
		Schedulable:             !node.Spec.Unschedulable,
		Roles:                   nodeRoles(node),
		InternalIP:              nodeInternalIP(node),
		KubeletVersion:          node.Status.NodeInfo.KubeletVersion,
		OSImage:                 node.Status.NodeInfo.OSImage,
		ContainerRuntimeVersion: node.Status.NodeInfo.ContainerRuntimeVersion,
		CPU:                     unknownValue,
		Memory:                  unknownValue,
		Pods:                    unknownValue,
	}
	if !node.CreationTimestamp.IsZero() {
		stamp := node.CreationTimestamp.UTC().Format("2006-01-02T15:04:05Z")
		view.CreationTimestamp = &stamp
	}
	if allocatable := node.Status.Allocatable; allocatable != nil {
		if cpu, ok := allocatable[corev1.ResourceCPU]; ok {
			view.CPU = cpu.String()
		}
		if memory, ok := allocatable[corev1.ResourceMemory]; ok {
			view.Memory = formatMemory(memory.String())
		}
		if pods, ok := allocatable[corev1.ResourcePods]; ok {
			view.Pods = pods.String()
		}
	}
	return view
}

func nodeReady(node *corev1.Node) bool {
	for _, condition := range node.Status.Conditions {
		if condition.Type == corev1.NodeReady && condition.Status == corev1.ConditionTrue {
			return true
		}
	}
	return false
}

// nodeHostname prefers the label, because that is what node affinity matches
// on; the object name is only a fallback for a node that somehow lacks it.
func nodeHostname(node *corev1.Node) string {
	if hostname := node.Labels[HostnameLabel]; hostname != "" {
		return hostname
	}
	return node.Name
}

func nodeRoles(node *corev1.Node) string {
	var roles []string
	seen := map[string]bool{}
	for label := range node.Labels {
		if !strings.HasPrefix(label, nodeRoleLabelPrefix) {
			continue
		}
		role := strings.TrimPrefix(label, nodeRoleLabelPrefix)
		if role == "" {
			role = "master"
		}
		if !seen[role] {
			seen[role] = true
			roles = append(roles, role)
		}
	}
	if len(roles) > 0 {
		sort.Strings(roles)
		return strings.Join(roles, ", ")
	}
	if legacy := node.Labels["kubernetes.io/role"]; legacy != "" {
		return legacy
	}
	return unknownValue
}

func nodeInternalIP(node *corev1.Node) string {
	for _, address := range node.Status.Addresses {
		if address.Type == corev1.NodeInternalIP && address.Address != "" {
			return address.Address
		}
	}
	for _, address := range node.Status.Addresses {
		if address.Address != "" {
			return address.Address
		}
	}
	return unknownValue
}

// formatMemory renders the kubelet's KiB allocatable as MB, which is what the
// node list shows. Anything in another unit is passed through untouched.
func formatMemory(quantity string) string {
	if quantity == "" {
		return unknownValue
	}
	if !strings.HasSuffix(quantity, "Ki") {
		return quantity
	}
	kibibytes, ok := new(big.Rat).SetString(strings.TrimSuffix(quantity, "Ki"))
	if !ok {
		return quantity
	}
	megabytes := kibibytes.Quo(kibibytes, big.NewRat(1024, 1))
	if megabytes.IsInt() {
		return megabytes.RatString() + " MB"
	}
	return megabytes.FloatString(1) + " MB"
}
