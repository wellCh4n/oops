package k8s

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/wellch4n/oops/server/internal/domain"
	corev1 "k8s.io/api/core/v1"
	schedulingv1 "k8s.io/api/scheduling/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// DeployInput is everything ArtifactDeployTask needs (DeployContext in Java).
type DeployInput struct {
	Pipeline      *domain.Pipeline
	Application   *domain.Application
	Environment   *domain.Environment
	RuntimeSpec   domain.RuntimeEnvironmentConfig
	HealthCheck   *domain.HealthCheck
	ServiceConfig *domain.ApplicationServiceConfig
	ExpertConfig  domain.ExpertEnvironmentConfig
	Domains       []domain.Domain
	CertResolver  string
}

// deployContext is the mutable state shared by the processors.
type deployContext struct {
	DeployInput
	client   *Client
	labels   map[string]string
	ownerRef *metav1.OwnerReference
}

func (c *deployContext) namespace() string { return c.Application.Namespace }
func (c *deployContext) appName() string   { return c.Application.Name }

type deployProcessor func(ctx context.Context, deploy *deployContext) error

// Deploy runs the six deploy processors in order with a fresh client. Errors
// are mapped like KubernetesArtifactDeploymentExecutor: an API status error
// becomes a plain error carrying the status message, anything else becomes
// "Failed to deploy artifact: <msg>".
func Deploy(ctx context.Context, in DeployInput) error {
	if in.Pipeline == nil || in.Application == nil || in.Environment == nil {
		return errors.New("Failed to deploy artifact: pipeline, application and environment are required")
	}
	client, err := New(in.Environment.KubernetesApiServer)
	if err != nil {
		return fmt.Errorf("Failed to deploy artifact: %s", err.Error())
	}
	if in.ServiceConfig == nil {
		in.ServiceConfig = in.Application.ServiceConfigOrDefault()
	}
	deploy := &deployContext{
		DeployInput: in,
		client:      client,
		labels:      ApplicationLabels(in.Application.Name),
	}
	processors := []deployProcessor{
		namespaceProcessor,
		imagePullSecretProcessor,
		priorityClassProcessor,
		statefulSetProcessor,
		serviceProcessor,
		ingressRouteProcessor,
	}
	for _, processor := range processors {
		if err := processor(ctx, deploy); err != nil {
			return mapDeployError(err)
		}
	}
	return nil
}

func mapDeployError(err error) error {
	var status *apierrors.StatusError
	if errors.As(err, &status) {
		message := status.ErrStatus.Message
		if message == "" {
			message = err.Error()
		}
		return errors.New(message)
	}
	return fmt.Errorf("Failed to deploy artifact: %s", err.Error())
}

// namespaceProcessor ensures the application namespace exists (SSA, no force).
func namespaceProcessor(ctx context.Context, deploy *deployContext) error {
	slog.Info("Checking namespace", "namespace", deploy.namespace())
	return ensureNamespace(ctx, deploy.client, deploy.namespace(), false)
}

func ensureNamespace(ctx context.Context, client *Client, namespace string, force bool) error {
	namespaceObject := &corev1.Namespace{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Namespace"},
		ObjectMeta: metav1.ObjectMeta{Name: namespace},
	}
	patch, err := applyPatch(namespaceObject)
	if err != nil {
		return err
	}
	_, err = client.Clientset.CoreV1().Namespaces().Patch(ctx, namespace, applyPatchType, patch, applyOptions(force))
	return err
}

// imagePullSecretProcessor copies the work namespace's dockerhub Secret into the application namespace.
func imagePullSecretProcessor(ctx context.Context, deploy *deployContext) error {
	slog.Info("Checking image pull secret for namespace", "namespace", deploy.namespace())
	workNamespace := domain.Deref(deploy.Environment.WorkNamespace)
	source, err := deploy.client.Clientset.CoreV1().Secrets(workNamespace).Get(ctx, ImagePullSecretName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil
		}
		return err
	}
	copied := &corev1.Secret{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{Name: ImagePullSecretName, Namespace: deploy.namespace()},
		Type:       source.Type,
		Data:       source.Data,
	}
	patch, err := applyPatch(copied)
	if err != nil {
		return err
	}
	_, err = deploy.client.Clientset.CoreV1().Secrets(deploy.namespace()).Patch(ctx, ImagePullSecretName, applyPatchType, patch, forceApply)
	return err
}

// priorityClassProcessor creates the PriorityClass the expert config asks for.
func priorityClassProcessor(ctx context.Context, deploy *deployContext) error {
	return EnsurePriorityClass(ctx, deploy.client, domain.PriorityFromValue(deploy.ExpertConfig.Priority))
}

// EnsurePriorityClass mirrors KubernetesPriorityClasses.ensure: NORMAL does
// nothing; otherwise the class is created once (POST) and never reconciled.
func EnsurePriorityClass(ctx context.Context, client *Client, priority domain.ApplicationPriority) error {
	name := priority.PriorityClassName()
	if name == "" {
		return nil
	}
	_, err := client.Clientset.SchedulingV1().PriorityClasses().Get(ctx, name, metav1.GetOptions{})
	if err == nil {
		return nil
	}
	if !IsNotFound(err) {
		return err
	}
	value := priority.PriorityValue()
	slog.Info("Creating PriorityClass", "name", name, "value", value)
	priorityClass := &schedulingv1.PriorityClass{
		ObjectMeta:    metav1.ObjectMeta{Name: name},
		Value:         value,
		GlobalDefault: false,
		Description:   fmt.Sprintf("Managed by OOPS — %s priority applications", lowerPriority(priority)),
	}
	_, err = client.Clientset.SchedulingV1().PriorityClasses().Create(ctx, priorityClass, metav1.CreateOptions{})
	if err != nil && IsAlreadyExists(err) {
		return nil
	}
	return err
}

func lowerPriority(priority domain.ApplicationPriority) string {
	switch priority {
	case domain.PriorityHigh:
		return "high"
	case domain.PriorityLow:
		return "low"
	default:
		return "normal"
	}
}

// serviceProcessor applies the ClusterIP Service when a port is configured.
func serviceProcessor(ctx context.Context, deploy *deployContext) error {
	if deploy.ServiceConfig == nil || deploy.ServiceConfig.Port == nil {
		return nil
	}
	slog.Info("Applying service for application", "namespace", deploy.namespace(), "application", deploy.appName())
	service := BuildService(deploy.namespace(), deploy.appName(), deploy.ServiceConfig, deploy.ownerRef)
	patch, err := applyPatch(service)
	if err != nil {
		return err
	}
	_, err = deploy.client.Clientset.CoreV1().Services(deploy.namespace()).Patch(ctx, deploy.appName(), applyPatchType, patch, forceApply)
	return err
}

// BuildService constructs the application Service manifest.
func BuildService(namespace, applicationName string, serviceConfig *domain.ApplicationServiceConfig, ownerRef *metav1.OwnerReference) *corev1.Service {
	appPort := *serviceConfig.Port
	ports := []corev1.ServicePort{{
		Name:       "web",
		Protocol:   corev1.ProtocolTCP,
		Port:       ServicePort,
		TargetPort: intstr.FromInt(appPort),
	}}
	for _, internalPort := range serviceConfig.DistinctInternalPorts() {
		if internalPort == ServicePort {
			continue
		}
		ports = append(ports, corev1.ServicePort{
			Name:       fmt.Sprintf("tcp-%d", internalPort),
			Protocol:   corev1.ProtocolTCP,
			Port:       int32(internalPort),
			TargetPort: intstr.FromInt(internalPort),
		})
	}
	service := &corev1.Service{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{
			Name:      applicationName,
			Namespace: namespace,
			Labels:    ApplicationLabels(applicationName),
		},
		Spec: corev1.ServiceSpec{
			Type:     corev1.ServiceTypeClusterIP,
			Selector: ApplicationLabels(applicationName),
			Ports:    ports,
		},
	}
	if ownerRef != nil {
		service.OwnerReferences = []metav1.OwnerReference{*ownerRef}
	}
	return service
}
