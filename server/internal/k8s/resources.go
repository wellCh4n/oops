package k8s

import (
	"context"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	sigsyaml "sigs.k8s.io/yaml"
)

// IngressRouteGVR is Traefik's CRD; absent on clusters without Traefik.
var IngressRouteGVR = schema.GroupVersionResource{
	Group: "traefik.io", Version: "v1alpha1", Resource: "ingressroutes",
}

// ResourceView mirrors ApplicationResourceView: kind, name, YAML manifest.
type ResourceView struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
	Data string `json:"data"`
}

func toResourceView(kind, name string, object any) ResourceView {
	encoded, err := sigsyaml.Marshal(object)
	if err != nil {
		return ResourceView{Kind: kind, Name: name, Data: ""}
	}
	return ResourceView{Kind: kind, Name: name, Data: string(encoded)}
}

// ListApplicationResources mirrors the expert-config gateway: the StatefulSet,
// labelled Services, and labelled IngressRoutes (skipped when the CRD is absent).
func ListApplicationResources(ctx context.Context, cluster *Cluster, namespace, applicationName string) ([]ResourceView, error) {
	resources := []ResourceView{}

	statefulSet, err := cluster.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if err == nil {
		statefulSet.ManagedFields = nil
		statefulSet.Kind, statefulSet.APIVersion = "StatefulSet", "apps/v1"
		resources = append(resources, toResourceView("StatefulSet", statefulSet.Name, statefulSet))
	} else if !apierrors.IsNotFound(err) {
		return nil, err
	}

	services, err := cluster.Clientset.CoreV1().Services(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationNameLabel + "=" + applicationName,
	})
	if err != nil {
		return nil, err
	}
	for i := range services.Items {
		service := &services.Items[i]
		service.ManagedFields = nil
		service.Kind, service.APIVersion = "Service", "v1"
		resources = append(resources, toResourceView("Service", service.Name, service))
	}

	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err == nil {
		ingressRoutes, err := dynamicClient.Resource(IngressRouteGVR).Namespace(namespace).List(ctx, metav1.ListOptions{
			LabelSelector: applicationNameLabel + "=" + applicationName,
		})
		if err == nil { // CRD may be absent — skip gracefully like the Java gateway
			for i := range ingressRoutes.Items {
				route := &ingressRoutes.Items[i]
				route.SetManagedFields(nil)
				resources = append(resources, toResourceView("IngressRoute", route.GetName(), route.Object))
			}
		}
	}
	return resources, nil
}

// FindCurrentImage mirrors findCurrentImage: the container named after the
// application, falling back to the first container.
func FindCurrentImage(ctx context.Context, cluster *Cluster, namespace, applicationName string) (*string, error) {
	statefulSet, err := cluster.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	containers := statefulSet.Spec.Template.Spec.Containers
	for _, container := range containers {
		if container.Name == applicationName {
			image := container.Image
			return &image, nil
		}
	}
	if len(containers) > 0 {
		image := containers[0].Image
		return &image, nil
	}
	return nil, nil
}
