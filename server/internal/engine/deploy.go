package engine

import (
	"context"
	"encoding/json"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

const (
	fieldManager                = "oops"
	rolloutStartedAtAnnotation  = "oops.rollout.started-at"
	pipelineIDAnnotation        = "oops.pipeline.id"
	redirectMiddlewareName      = "oops-redirect-https"
	basicAuthLabelKey           = "oops.resource"
	basicAuthLabelValue         = "basic-auth"
	deployServicePort           = 80
	applicationTypeLabel        = "oops.type"
	applicationNameLabel        = "oops.app.name"
	applicationTypeLabelValue   = "APPLICATION"
	dockerhubSecretName         = "dockerhub"
	defaultImagePullSecret      = "dockerhub"
	statefulSetKind             = "StatefulSet"
	statefulSetAPIVersion       = "apps/v1"
	middlewareResourceName      = "middlewares"
	traefikGroup                = "traefik.io"
	traefikVersion              = "v1alpha1"
	ingressRouteCRDResourceName = "ingressroutes.traefik.io"
)

var middlewareGVR = schema.GroupVersionResource{Group: traefikGroup, Version: traefikVersion, Resource: middlewareResourceName}

// deployInput is the Go DeployContext.
type deployInput struct {
	Pipeline      *store.PipelineView
	Namespace     string
	Application   string
	Environment   *store.EnvironmentFull
	RuntimeSpec   *store.RuntimeEnvironmentConfig
	HealthCheck   *store.HealthCheck
	ServiceConfig *store.ServiceConfigView
	ExpertConfig  *store.ExpertEnvironmentConfig
	CertResolver  string
	Domains       []store.DomainFull
}

func applicationLabels(applicationName string) map[string]string {
	return map[string]string{
		applicationTypeLabel: applicationTypeLabelValue,
		applicationNameLabel: applicationName,
	}
}

func serverSideApply(ctx context.Context, cluster *k8s.Cluster, object any, gvr schema.GroupVersionResource, namespace, name string) error {
	payload, err := json.Marshal(object)
	if err != nil {
		return err
	}
	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err != nil {
		return err
	}
	_, err = dynamicClient.Resource(gvr).Namespace(namespace).Patch(ctx, name, types.ApplyPatchType, payload,
		metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
	return err
}

func boolPointer(value bool) *bool { return &value }

// Deploy runs the processor chain, mirroring ArtifactDeployTask.
func Deploy(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	if err := processNamespace(ctx, cluster, input); err != nil {
		return err
	}
	if err := processImagePullSecret(ctx, cluster, input); err != nil {
		return err
	}
	if err := processPriorityClass(ctx, cluster, input); err != nil {
		return err
	}
	ownerReference, err := processStatefulSet(ctx, cluster, input)
	if err != nil {
		return err
	}
	if err := processService(ctx, cluster, input, ownerReference); err != nil {
		return err
	}
	return processIngressRoutes(ctx, cluster, input, ownerReference)
}

func processNamespace(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	namespace := map[string]any{
		"apiVersion": "v1", "kind": "Namespace",
		"metadata": map[string]any{"name": input.Namespace},
	}
	return serverSideApply(ctx, cluster, namespace,
		schema.GroupVersionResource{Version: "v1", Resource: "namespaces"}, "", input.Namespace)
}

func processImagePullSecret(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	workNamespace := ""
	if input.Environment.WorkNamespace != nil {
		workNamespace = *input.Environment.WorkNamespace
	}
	if workNamespace == "" {
		return nil
	}
	source, err := cluster.Clientset.CoreV1().Secrets(workNamespace).Get(ctx, dockerhubSecretName, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	if err != nil {
		return err
	}
	target := &corev1.Secret{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{Name: dockerhubSecretName, Namespace: input.Namespace},
		Type:       source.Type,
		Data:       source.Data,
	}
	return serverSideApply(ctx, cluster, target,
		schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, dockerhubSecretName)
}
