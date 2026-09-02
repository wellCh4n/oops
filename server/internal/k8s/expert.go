package k8s

import (
	"context"
	"log/slog"

	"github.com/wellch4n/oops/server/internal/domain"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/yaml"
)

// ExpertConfigGateway is the Go counterpart of KubernetesApplicationExpertConfigGateway.
type ExpertConfigGateway struct{ pool *Pool }

func NewExpertConfigGateway(pool *Pool) *ExpertConfigGateway { return &ExpertConfigGateway{pool: pool} }

// ApplyExpertConfig mirrors spec-deploy §6.1: no-op when the StatefulSet is
// absent; otherwise ensure the PriorityClass and edit the pod template.
func (g *ExpertConfigGateway) ApplyExpertConfig(ctx context.Context, environment *domain.Environment, namespace, applicationName string, config domain.ExpertEnvironmentConfig) error {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	return TranslateError(applyExpertConfig(ctx, client, namespace, applicationName, config))
}

func applyExpertConfig(ctx context.Context, client *Client, namespace, applicationName string, config domain.ExpertEnvironmentConfig) error {
	statefulSets := client.Clientset.AppsV1().StatefulSets(namespace)
	statefulSet, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil
		}
		return err
	}
	priority := domain.PriorityFromValue(config.Priority)
	if err := EnsurePriorityClass(ctx, client, priority); err != nil {
		return err
	}
	podSpec := &statefulSet.Spec.Template.Spec
	if blankPtr(config.ServiceAccountName) {
		podSpec.ServiceAccountName = DefaultServiceAccount
	} else {
		podSpec.ServiceAccountName = *config.ServiceAccountName
	}
	podSpec.PriorityClassName = priority.PriorityClassName()
	podSpec.Affinity = RequireNodesAffinity(config.NodeNames)
	_, err = statefulSets.Update(ctx, statefulSet, metav1.UpdateOptions{})
	return err
}

// ResourceView mirrors ApplicationResourceView: Data is the resource as YAML.
type ResourceView struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
	Data string `json:"data"`
}

// GetApplicationResources returns the StatefulSet, Services and IngressRoutes
// of an application serialized as YAML with managedFields stripped.
func (g *ExpertConfigGateway) GetApplicationResources(ctx context.Context, environment *domain.Environment, namespace, applicationName string) ([]ResourceView, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	views := []ResourceView{}

	statefulSet, err := client.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil && !IsNotFound(err) {
		return nil, TranslateError(err)
	}
	if err == nil {
		statefulSet.APIVersion, statefulSet.Kind = "apps/v1", "StatefulSet"
		view, err := resourceViewOf(statefulSet, "StatefulSet", statefulSet.Name)
		if err != nil {
			return nil, err
		}
		views = append(views, view)
	}

	services, err := client.Clientset.CoreV1().Services(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationNameSelector(applicationName)})
	if err != nil {
		return nil, TranslateError(err)
	}
	for index := range services.Items {
		service := &services.Items[index]
		service.APIVersion, service.Kind = "v1", "Service"
		view, err := resourceViewOf(service, "Service", service.Name)
		if err != nil {
			return nil, err
		}
		views = append(views, view)
	}

	routes, err := client.Dynamic.Resource(IngressRouteGVR).Namespace(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationNameSelector(applicationName)})
	if err != nil {
		slog.Warn("Failed to read IngressRoutes", "namespace", namespace, "application", applicationName, "error", err.Error())
		return views, nil
	}
	for index := range routes.Items {
		route := &routes.Items[index]
		view, err := resourceViewOf(route, "IngressRoute", route.GetName())
		if err != nil {
			return nil, err
		}
		views = append(views, view)
	}
	return views, nil
}

// resourceViewOf serializes the object as YAML without metadata.managedFields.
func resourceViewOf(object runtime.Object, kind, name string) (ResourceView, error) {
	content, err := runtime.DefaultUnstructuredConverter.ToUnstructured(object)
	if err != nil {
		return ResourceView{}, err
	}
	if metadata, ok := content["metadata"].(map[string]any); ok {
		delete(metadata, "managedFields")
	}
	encoded, err := yaml.Marshal(content)
	if err != nil {
		return ResourceView{}, err
	}
	return ResourceView{Kind: kind, Name: name, Data: string(encoded)}, nil
}
