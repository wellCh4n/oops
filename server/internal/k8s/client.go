// Package k8s is the cluster gateway, the Go counterpart of
// infrastructure/kubernetes. Clients are built per environment from the URL +
// (decrypted) token stored on the Environment, like KubernetesClients.from().
package k8s

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
)

// Cluster bundles the typed clientset with the rest.Config it was built from,
// so aggregated APIs (metrics.k8s.io) can build their own clients.
type Cluster struct {
	Clientset *kubernetes.Clientset
	Config    *rest.Config
}

func NewCluster(apiServerURL, token string) (*Cluster, error) {
	restConfig, err := newRESTConfig(apiServerURL, token)
	if err != nil {
		return nil, err
	}
	clientset, err := kubernetes.NewForConfig(restConfig)
	if err != nil {
		return nil, err
	}
	return &Cluster{Clientset: clientset, Config: restConfig}, nil
}

func newRESTConfig(apiServerURL, token string) (*rest.Config, error) {
	// Fabric8 falls back to the local kubeconfig (then in-cluster config) when
	// the Environment carries no explicit API server; mirror that so
	// environments configured with blank credentials keep working.
	if apiServerURL == "" {
		loadingRules := clientcmd.NewDefaultClientConfigLoadingRules()
		restConfig, err := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(
			loadingRules, &clientcmd.ConfigOverrides{}).ClientConfig()
		if err != nil {
			restConfig, err = rest.InClusterConfig()
			if err != nil {
				return nil, fmt.Errorf("no api server configured and no kubeconfig found: %w", err)
			}
		}
		return restConfig, nil
	}
	return &rest.Config{
		Host:        apiServerURL,
		BearerToken: token,
		// Fabric8 on the Java side connects with trustCerts semantics — the
		// Environment stores no CA bundle to verify against.
		// No client-wide Timeout: it would kill long-lived pod watches; the
		// callers bound individual requests with contexts instead.
		TLSClientConfig: rest.TLSClientConfig{Insecure: true},
	}, nil
}

// formatMemory renders a Ki quantity as "N MB" with one decimal (trailing
// zeros stripped), matching KubernetesClusterNodeGateway.quantityToString.
func formatMemory(quantity string) string {
	kib, found := strings.CutSuffix(quantity, "Ki")
	if !found {
		return quantity
	}
	value, err := strconv.ParseFloat(kib, 64)
	if err != nil {
		return quantity
	}
	mb := value / 1024
	rendered := strconv.FormatFloat(mb, 'f', 1, 64)
	rendered = strings.TrimSuffix(rendered, ".0")
	return rendered + " MB"
}

// NodeStatusView mirrors application/dto/NodeStatusView.
type NodeStatusView struct {
	Name                    string `json:"name"`
	Hostname                string `json:"hostname"`
	Ready                   bool   `json:"ready"`
	Schedulable             bool   `json:"schedulable"`
	Roles                   string `json:"roles"`
	InternalIP              string `json:"internalIP"`
	KubeletVersion          string `json:"kubeletVersion"`
	OsImage                 string `json:"osImage"`
	ContainerRuntimeVersion string `json:"containerRuntimeVersion"`
	CPU                     string `json:"cpu"`
	Memory                  string `json:"memory"`
	Pods                    string `json:"pods"`
	CreationTimestamp       string `json:"creationTimestamp"`
}

func ListNodes(ctx context.Context, client *kubernetes.Clientset) ([]NodeStatusView, error) {
	nodeList, err := client.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list nodes: %w", err)
	}
	views := make([]NodeStatusView, 0, len(nodeList.Items))
	for _, node := range nodeList.Items {
		view := NodeStatusView{
			Name:                    node.Name,
			Schedulable:             !node.Spec.Unschedulable,
			KubeletVersion:          node.Status.NodeInfo.KubeletVersion,
			OsImage:                 node.Status.NodeInfo.OSImage,
			ContainerRuntimeVersion: node.Status.NodeInfo.ContainerRuntimeVersion,
			CPU:                     node.Status.Allocatable.Cpu().String(),
			Memory:                  formatMemory(node.Status.Allocatable.Memory().String()),
			Pods:                    node.Status.Allocatable.Pods().String(),
			CreationTimestamp:       node.CreationTimestamp.UTC().Format(time.RFC3339),
		}
		for _, address := range node.Status.Addresses {
			switch address.Type {
			case corev1.NodeInternalIP:
				if view.InternalIP == "" { // first InternalIP wins, like the Java gateway
					view.InternalIP = address.Address
				}
			case corev1.NodeHostName:
				if view.Hostname == "" {
					view.Hostname = address.Address
				}
			}
		}
		for _, condition := range node.Status.Conditions {
			if condition.Type == corev1.NodeReady {
				view.Ready = condition.Status == corev1.ConditionTrue
			}
		}
		roles := []string{}
		for label := range node.Labels {
			if role, found := strings.CutPrefix(label, "node-role.kubernetes.io/"); found && role != "" {
				roles = append(roles, role)
			}
		}
		view.Roles = strings.Join(roles, ",")
		views = append(views, view)
	}
	return views, nil
}
