package engine

import (
	"context"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func processService(ctx context.Context, cluster *k8s.Cluster, input *deployInput, owner *metav1.OwnerReference) error {
	if input.ServiceConfig == nil || input.ServiceConfig.Port == nil {
		return nil
	}
	applicationPort := *input.ServiceConfig.Port
	labels := applicationLabels(input.Application)
	ports := []corev1.ServicePort{{
		Name: "web", Protocol: corev1.ProtocolTCP,
		Port: deployServicePort, TargetPort: intstr.FromInt(applicationPort),
	}}
	for _, internalPort := range distinctInternalPorts(input.ServiceConfig) {
		if internalPort == deployServicePort {
			continue
		}
		ports = append(ports, corev1.ServicePort{
			Name: fmt.Sprintf("tcp-%d", internalPort), Protocol: corev1.ProtocolTCP,
			Port: int32(internalPort), TargetPort: intstr.FromInt(internalPort),
		})
	}
	service := &corev1.Service{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{
			Name: input.Application, Namespace: input.Namespace,
			Labels:          labels,
			OwnerReferences: []metav1.OwnerReference{*owner},
		},
		Spec: corev1.ServiceSpec{
			Type:     corev1.ServiceTypeClusterIP,
			Selector: labels,
			Ports:    ports,
		},
	}
	return serverSideApply(ctx, cluster, service,
		schema.GroupVersionResource{Version: "v1", Resource: "services"}, input.Namespace, input.Application)
}
