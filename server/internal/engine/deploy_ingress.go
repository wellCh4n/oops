package engine

import (
	"context"
	"encoding/json"
	"strings"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

func ingressRouteName(applicationName, host, suffix string) string {
	return applicationName + "-" + suffix + "-" + strings.ReplaceAll(host, ".", "-")
}

func basicAuthResourceName(applicationName, host string) string {
	return applicationName + "-basic-auth-" + strings.ReplaceAll(host, ".", "-")
}

func processIngressRoutes(ctx context.Context, cluster *k8s.Cluster, input *deployInput, owner *metav1.OwnerReference) error {
	if input.ServiceConfig == nil {
		return nil
	}
	hostConfigs := []store.ServiceEnvironmentConfigStored{}
	for _, config := range input.ServiceConfig.StoredEnvironmentConfigs {
		if config.EnvironmentName != nil && *config.EnvironmentName == input.Environment.Name &&
			config.Host != nil && *config.Host != "" {
			hostConfigs = append(hostConfigs, config)
		}
	}
	if len(hostConfigs) == 0 {
		return nil
	}

	// Skip gracefully when the Traefik CRD is absent, like the Java processor.
	if _, err := cluster.Clientset.Discovery().ServerResourcesForGroupVersion(traefikGroup + "/" + traefikVersion); err != nil {
		return nil
	}
	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err != nil {
		return err
	}

	labels := applicationLabels(input.Application)
	ownerMap := []any{map[string]any{
		"apiVersion": owner.APIVersion, "kind": owner.Kind, "name": owner.Name,
		"uid": string(owner.UID), "controller": true, "blockOwnerDeletion": true,
	}}

	applyRoute := func(name, host string, entryPoints []string, tls map[string]any, middlewares []string) error {
		route := map[string]any{
			"match": "Host(`" + host + "`)",
			"kind":  "Rule",
			"services": []any{map[string]any{
				"name": input.Application, "port": deployServicePort,
			}},
		}
		if len(middlewares) > 0 {
			middlewareRefs := []any{}
			for _, middlewareName := range middlewares {
				middlewareRefs = append(middlewareRefs, map[string]any{"name": middlewareName})
			}
			route["middlewares"] = middlewareRefs
		}
		spec := map[string]any{
			"routes":      []any{route},
			"entryPoints": entryPoints,
		}
		if tls != nil {
			spec["tls"] = tls
		}
		object := map[string]any{
			"apiVersion": traefikGroup + "/" + traefikVersion,
			"kind":       "IngressRoute",
			"metadata": map[string]any{
				"name": name, "namespace": input.Namespace,
				"labels": labels, "ownerReferences": ownerMap,
			},
			"spec": spec,
		}
		payload, err := json.Marshal(object)
		if err != nil {
			return err
		}
		_, err = dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
			Patch(ctx, name, types.ApplyPatchType, payload,
				metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
		return err
	}

	appliedNames := map[string]struct{}{}
	appliedBasicAuth := map[string]struct{}{}
	for _, config := range hostConfigs {
		host := *config.Host
		https := config.HTTPS != nil && *config.HTTPS

		serveMiddlewares := []string{}
		if config.BasicAuthEnabled != nil && *config.BasicAuthEnabled &&
			config.BasicAuthUsername != nil && *config.BasicAuthUsername != "" &&
			config.BasicAuthPasswordHash != nil && *config.BasicAuthPasswordHash != "" {
			basicAuthName := basicAuthResourceName(input.Application, host)
			if err := ensureBasicAuthMiddleware(ctx, cluster, dynamicClient, input, basicAuthName,
				*config.BasicAuthUsername, *config.BasicAuthPasswordHash, owner); err != nil {
				return err
			}
			appliedBasicAuth[basicAuthName] = struct{}{}
			serveMiddlewares = []string{basicAuthName}
		}

		if https {
			if err := ensureRedirectMiddleware(ctx, dynamicClient, input.Namespace); err != nil {
				return err
			}
			httpName := ingressRouteName(input.Application, host, "http")
			appliedNames[httpName] = struct{}{}
			if err := applyRoute(httpName, host, []string{"web"}, nil, []string{redirectMiddlewareName}); err != nil {
				return err
			}
			httpsName := ingressRouteName(input.Application, host, "https")
			appliedNames[httpsName] = struct{}{}
			tls, err := buildTLSForHost(ctx, cluster, input, host)
			if err != nil {
				return err
			}
			if err := applyRoute(httpsName, host, []string{"websecure"}, tls, serveMiddlewares); err != nil {
				return err
			}
		} else {
			httpName := ingressRouteName(input.Application, host, "http")
			appliedNames[httpName] = struct{}{}
			if err := applyRoute(httpName, host, []string{"web"}, nil, serveMiddlewares); err != nil {
				return err
			}
		}
	}

	// Delete IngressRoutes for hosts no longer configured.
	existing, err := dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
		List(ctx, metav1.ListOptions{LabelSelector: applicationNameLabel + "=" + input.Application})
	if err == nil {
		for _, item := range existing.Items {
			if _, kept := appliedNames[item.GetName()]; !kept {
				_ = dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
			}
		}
	}
	// Delete stale basic-auth middlewares + secrets.
	staleMiddlewares, err := dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
		List(ctx, metav1.ListOptions{
			LabelSelector: applicationNameLabel + "=" + input.Application + "," + basicAuthLabelKey + "=" + basicAuthLabelValue,
		})
	if err == nil {
		for _, item := range staleMiddlewares.Items {
			if _, kept := appliedBasicAuth[item.GetName()]; !kept {
				_ = dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
				_ = cluster.Clientset.CoreV1().Secrets(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
			}
		}
	}
	return nil
}

func ensureRedirectMiddleware(ctx context.Context, dynamicClient dynamic.Interface, namespace string) error {
	_, err := dynamicClient.Resource(middlewareGVR).Namespace(namespace).Get(ctx, redirectMiddlewareName, metav1.GetOptions{})
	if err == nil {
		return nil
	}
	if !apierrors.IsNotFound(err) {
		return err
	}
	middleware := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": traefikGroup + "/" + traefikVersion,
		"kind":       "Middleware",
		"metadata":   map[string]any{"name": redirectMiddlewareName, "namespace": namespace},
		"spec": map[string]any{
			"redirectScheme": map[string]any{"scheme": "https", "permanent": true},
		},
	}}
	_, err = dynamicClient.Resource(middlewareGVR).Namespace(namespace).Create(ctx, middleware, metav1.CreateOptions{})
	if apierrors.IsAlreadyExists(err) {
		return nil
	}
	return err
}

func ensureBasicAuthMiddleware(ctx context.Context, cluster *k8s.Cluster, dynamicClient dynamic.Interface,
	input *deployInput, resourceName, username, passwordHash string, owner *metav1.OwnerReference) error {

	labels := applicationLabels(input.Application)
	labels[basicAuthLabelKey] = basicAuthLabelValue

	secret := &corev1.Secret{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{
			Name: resourceName, Namespace: input.Namespace,
			Labels:          labels,
			OwnerReferences: []metav1.OwnerReference{*owner},
		},
		Type:       corev1.SecretTypeOpaque,
		StringData: map[string]string{"users": username + ":" + passwordHash},
	}
	if err := serverSideApply(ctx, cluster, secret,
		schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, resourceName); err != nil {
		return err
	}
	middleware := map[string]any{
		"apiVersion": traefikGroup + "/" + traefikVersion,
		"kind":       "Middleware",
		"metadata": map[string]any{
			"name": resourceName, "namespace": input.Namespace, "labels": labels,
			"ownerReferences": []any{map[string]any{
				"apiVersion": owner.APIVersion, "kind": owner.Kind, "name": owner.Name,
				"uid": string(owner.UID), "controller": true, "blockOwnerDeletion": true,
			}},
		},
		"spec": map[string]any{"basicAuth": map[string]any{"secret": resourceName}},
	}
	payload, err := json.Marshal(middleware)
	if err != nil {
		return err
	}
	_, err = dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
		Patch(ctx, resourceName, types.ApplyPatchType, payload,
			metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
	return err
}

// buildTLSForHost mirrors buildTlsForHost: longest-suffix domain match;
// UPLOADED domains sync a TLS secret, otherwise the cert resolver is used.
func buildTLSForHost(ctx context.Context, cluster *k8s.Cluster, input *deployInput, host string) (map[string]any, error) {
	var matched *store.DomainFull
	longest := -1
	for i := range input.Domains {
		domain := &input.Domains[i]
		if domain.Host == "" {
			continue
		}
		if (host == domain.Host || strings.HasSuffix(host, "."+domain.Host)) && len(domain.Host) > longest {
			matched = domain
			longest = len(domain.Host)
		}
	}
	if matched != nil && matched.CertMode != nil && *matched.CertMode == "UPLOADED" &&
		matched.CertPem != "" && matched.KeyPem != "" {
		secretName := "domain-" + strings.ReplaceAll(matched.Host, ".", "-")
		secret := &corev1.Secret{
			TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
			ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: input.Namespace},
			Type:       corev1.SecretTypeTLS,
			StringData: map[string]string{"tls.crt": matched.CertPem, "tls.key": matched.KeyPem},
		}
		if err := serverSideApply(ctx, cluster, secret,
			schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, secretName); err != nil {
			return nil, err
		}
		return map[string]any{"secretName": secretName}, nil
	}
	return map[string]any{"certResolver": input.CertResolver}, nil
}
