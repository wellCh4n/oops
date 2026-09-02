package k8s

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/wellch4n/oops/server/internal/domain"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

const tlsSecretSyncAttempts = 3

// ingressRouteProcessor mirrors IngressRouteProcessor.process.
func ingressRouteProcessor(ctx context.Context, deploy *deployContext) error {
	namespace, appName := deploy.namespace(), deploy.appName()

	if _, err := deploy.client.Dynamic.Resource(crdGVR).Get(ctx, ingressRouteCRDName, metav1.GetOptions{}); err != nil {
		if IsNotFound(err) {
			slog.Warn("Could not find ingress route crd")
			return nil
		}
		return err
	}

	var hostConfigs []domain.ServiceEnvironmentConfig
	for _, config := range deploy.ServiceConfig.EnvironmentConfigsFor(deploy.Environment.Name) {
		if !blankPtr(config.Host) {
			hostConfigs = append(hostConfigs, config)
		}
	}
	if len(hostConfigs) == 0 {
		slog.Info("No host configured for application, withdrawing any ingress routes", "namespace", namespace, "application", appName, "environment", deploy.Environment.Name)
	}

	appliedNames := map[string]bool{}
	appliedBasicAuthNames := map[string]bool{}
	for _, config := range hostConfigs {
		host := *config.Host
		https := config.HTTPS != nil && *config.HTTPS
		var serveMiddlewares []string
		if config.BasicAuthConfigured() {
			basicAuthName := appName + "-basic-auth-" + dashHost(host)
			if err := ensureBasicAuthMiddleware(ctx, deploy, basicAuthName, *config.BasicAuthUsername, *config.BasicAuthPasswordHash); err != nil {
				return err
			}
			appliedBasicAuthNames[basicAuthName] = true
			serveMiddlewares = []string{basicAuthName}
		}
		httpName := appName + "-http-" + dashHost(host)
		if https {
			if err := ensureRedirectMiddleware(ctx, deploy); err != nil {
				return err
			}
			if err := applyIngressRoute(ctx, deploy, httpName, host, []string{"web"}, nil, []string{RedirectMiddlewareName}); err != nil {
				return err
			}
			appliedNames[httpName] = true
			httpsName := appName + "-https-" + dashHost(host)
			tls, err := buildTLSForHost(ctx, deploy, host)
			if err != nil {
				return err
			}
			if err := applyIngressRoute(ctx, deploy, httpsName, host, []string{"websecure"}, tls, serveMiddlewares); err != nil {
				return err
			}
			appliedNames[httpsName] = true
		} else {
			if err := applyIngressRoute(ctx, deploy, httpName, host, []string{"web"}, nil, serveMiddlewares); err != nil {
				return err
			}
			appliedNames[httpName] = true
		}
	}

	// Prune IngressRoutes no longer configured.
	routes, err := deploy.client.Dynamic.Resource(IngressRouteGVR).Namespace(namespace).List(ctx, metav1.ListOptions{LabelSelector: ApplicationNameSelector(appName)})
	if err != nil {
		return err
	}
	for _, route := range routes.Items {
		if appliedNames[route.GetName()] {
			continue
		}
		if err := deploy.client.Dynamic.Resource(IngressRouteGVR).Namespace(namespace).Delete(ctx, route.GetName(), metav1.DeleteOptions{}); err != nil && !IsNotFound(err) {
			return err
		}
	}

	// Prune basic-auth Middlewares and their Secrets.
	middlewares, err := deploy.client.Dynamic.Resource(MiddlewareGVR).Namespace(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: ApplicationNameSelector(appName) + "," + BasicAuthLabelKey + "=" + BasicAuthLabelValue,
	})
	if err != nil {
		return err
	}
	for _, middleware := range middlewares.Items {
		name := middleware.GetName()
		if appliedBasicAuthNames[name] {
			continue
		}
		if err := deploy.client.Dynamic.Resource(MiddlewareGVR).Namespace(namespace).Delete(ctx, name, metav1.DeleteOptions{}); err != nil && !IsNotFound(err) {
			return err
		}
		if err := deploy.client.Clientset.CoreV1().Secrets(namespace).Delete(ctx, name, metav1.DeleteOptions{}); err != nil && !IsNotFound(err) {
			return err
		}
	}
	return nil
}

// IngressRouteTLS is the tls block of an IngressRoute. Exactly one of the two is
// set: an uploaded certificate names a Secret, and AUTO mode names the resolver
// that will obtain one.
type IngressRouteTLS struct {
	CertResolver *string
	SecretName   *string
}

// BuildIngressRoute constructs the Traefik IngressRoute manifest of spec-deploy §1.8.
func BuildIngressRoute(namespace, applicationName, resourceName, host string, entryPoints []string, tls *IngressRouteTLS, middlewares []string, ownerRef *metav1.OwnerReference) *unstructured.Unstructured {
	route := map[string]any{
		"match": fmt.Sprintf("Host(`%s`)", host),
		"kind":  "Rule",
		"services": []any{map[string]any{
			"name": applicationName,
			"port": int64(ServicePort),
		}},
	}
	if len(middlewares) > 0 {
		middlewareRefs := make([]any, 0, len(middlewares))
		for _, name := range middlewares {
			middlewareRefs = append(middlewareRefs, map[string]any{"name": name})
		}
		route["middlewares"] = middlewareRefs
	}
	entryPointValues := make([]any, 0, len(entryPoints))
	for _, entryPoint := range entryPoints {
		entryPointValues = append(entryPointValues, entryPoint)
	}
	spec := map[string]any{
		"entryPoints": entryPointValues,
		"routes":      []any{route},
	}
	if tls != nil {
		// Only the field that is actually set is emitted. The CRD schema types
		// both as strings and rejects an explicit null, so writing the unused one
		// as null fails the whole apply — which took every HTTPS deploy down with
		// it. An empty block is valid and means "default TLS".
		tlsBlock := map[string]any{}
		if !blankPtr(tls.CertResolver) {
			tlsBlock["certResolver"] = *tls.CertResolver
		}
		if !blankPtr(tls.SecretName) {
			tlsBlock["secretName"] = *tls.SecretName
		}
		spec["tls"] = tlsBlock
	}
	object := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": traefikAPIVersion,
		"kind":       "IngressRoute",
		"metadata":   traefikMetadata(namespace, resourceName, ApplicationLabels(applicationName), ownerRef),
		"spec":       spec,
	}}
	return object
}

// BuildBasicAuthMiddleware constructs the Traefik basicAuth Middleware.
func BuildBasicAuthMiddleware(namespace, applicationName, name string, ownerRef *metav1.OwnerReference) *unstructured.Unstructured {
	labels := basicAuthLabels(applicationName)
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": traefikAPIVersion,
		"kind":       "Middleware",
		"metadata":   traefikMetadata(namespace, name, labels, ownerRef),
		"spec": map[string]any{
			"basicAuth": map[string]any{"secret": name},
		},
	}}
}

// BuildBasicAuthSecret constructs the htpasswd Secret consumed by the Middleware.
func BuildBasicAuthSecret(namespace, applicationName, name, username, passwordHash string, ownerRef *metav1.OwnerReference) *corev1.Secret {
	secret := &corev1.Secret{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: namespace,
			Labels:    basicAuthLabels(applicationName),
		},
		Type: corev1.SecretTypeOpaque,
		Data: map[string][]byte{"users": []byte(username + ":" + passwordHash)},
	}
	if ownerRef != nil {
		secret.OwnerReferences = []metav1.OwnerReference{*ownerRef}
	}
	return secret
}

// BuildRedirectMiddleware constructs the namespace-shared HTTPS redirect Middleware.
func BuildRedirectMiddleware(namespace string) *unstructured.Unstructured {
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": traefikAPIVersion,
		"kind":       "Middleware",
		"metadata":   map[string]any{"name": RedirectMiddlewareName, "namespace": namespace},
		"spec": map[string]any{
			"redirectScheme": map[string]any{"scheme": "https", "permanent": true},
		},
	}}
}

// BuildTLSSecret constructs the kubernetes.io/tls Secret for an uploaded domain certificate.
func BuildTLSSecret(namespace string, domainHost, certPem, keyPem string) *corev1.Secret {
	return &corev1.Secret{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{Name: TLSSecretName(domainHost), Namespace: namespace},
		Type:       corev1.SecretTypeTLS,
		Data: map[string][]byte{
			"tls.crt": []byte(certPem),
			"tls.key": []byte(keyPem),
		},
	}
}

// TLSSecretName returns "domain-<host with . -> ->".
func TLSSecretName(domainHost string) string { return "domain-" + dashHost(domainHost) }

func basicAuthLabels(applicationName string) map[string]string {
	labels := ApplicationLabels(applicationName)
	labels[BasicAuthLabelKey] = BasicAuthLabelValue
	return labels
}

func traefikMetadata(namespace, name string, labels map[string]string, ownerRef *metav1.OwnerReference) map[string]any {
	labelValues := map[string]any{}
	for key, value := range labels {
		labelValues[key] = value
	}
	metadata := map[string]any{
		"name":      name,
		"namespace": namespace,
		"labels":    labelValues,
	}
	if ownerRef != nil {
		metadata["ownerReferences"] = []any{map[string]any{
			"apiVersion":         ownerRef.APIVersion,
			"kind":               ownerRef.Kind,
			"name":               ownerRef.Name,
			"uid":                string(ownerRef.UID),
			"controller":         true,
			"blockOwnerDeletion": true,
		}}
	}
	return metadata
}

func applyIngressRoute(ctx context.Context, deploy *deployContext, resourceName, host string, entryPoints []string, tls *IngressRouteTLS, middlewares []string) error {
	route := BuildIngressRoute(deploy.namespace(), deploy.appName(), resourceName, host, entryPoints, tls, middlewares, deploy.ownerRef)
	patch, err := applyUnstructured(route)
	if err != nil {
		return err
	}
	_, err = deploy.client.Dynamic.Resource(IngressRouteGVR).Namespace(deploy.namespace()).Patch(ctx, resourceName, applyPatchType, patch, forceApply)
	if err != nil {
		slog.Error("Error applying ingress route", "namespace", deploy.namespace(), "name", resourceName, "error", err.Error())
	}
	return err
}

func ensureRedirectMiddleware(ctx context.Context, deploy *deployContext) error {
	middlewares := deploy.client.Dynamic.Resource(MiddlewareGVR).Namespace(deploy.namespace())
	if _, err := middlewares.Get(ctx, RedirectMiddlewareName, metav1.GetOptions{}); err == nil {
		return nil
	} else if !IsNotFound(err) {
		return err
	}
	patch, err := applyUnstructured(BuildRedirectMiddleware(deploy.namespace()))
	if err != nil {
		return err
	}
	if _, err := middlewares.Patch(ctx, RedirectMiddlewareName, applyPatchType, patch, forceApply); err != nil {
		return err
	}
	slog.Info("Created redirect middleware", "namespace", deploy.namespace(), "name", RedirectMiddlewareName)
	return nil
}

func ensureBasicAuthMiddleware(ctx context.Context, deploy *deployContext, name, username, passwordHash string) error {
	secretPatch, err := applyPatch(BuildBasicAuthSecret(deploy.namespace(), deploy.appName(), name, username, passwordHash, deploy.ownerRef))
	if err != nil {
		return err
	}
	if _, err := deploy.client.Clientset.CoreV1().Secrets(deploy.namespace()).Patch(ctx, name, applyPatchType, secretPatch, forceApply); err != nil {
		slog.Error("Error applying basic auth middleware", "namespace", deploy.namespace(), "name", name, "error", err.Error())
		return err
	}
	middlewarePatch, err := applyUnstructured(BuildBasicAuthMiddleware(deploy.namespace(), deploy.appName(), name, deploy.ownerRef))
	if err != nil {
		return err
	}
	if _, err := deploy.client.Dynamic.Resource(MiddlewareGVR).Namespace(deploy.namespace()).Patch(ctx, name, applyPatchType, middlewarePatch, forceApply); err != nil {
		slog.Error("Error applying basic auth middleware", "namespace", deploy.namespace(), "name", name, "error", err.Error())
		return err
	}
	return nil
}

// ResolveTLS picks the tls block for a host: an uploaded certificate of the
// longest matching domain, otherwise the cert resolver. The returned domain is
// non-nil only when its TLS Secret must be synced.
func ResolveTLS(host string, domains []domain.Domain, certResolver string) (*IngressRouteTLS, *domain.Domain) {
	matched := domain.FindBestDomainMatch(host, domains)
	if matched != nil && matched.CertMode != nil && *matched.CertMode == domain.CertModeUploaded &&
		!blankPtr(matched.CertPem) && !blankPtr(matched.KeyPem) {
		return &IngressRouteTLS{SecretName: domain.Ptr(TLSSecretName(*matched.Host))}, matched
	}
	var resolver *string
	if certResolver != "" {
		resolver = domain.Ptr(certResolver)
	}
	return &IngressRouteTLS{CertResolver: resolver}, nil
}

func buildTLSForHost(ctx context.Context, deploy *deployContext, host string) (*IngressRouteTLS, error) {
	tls, uploaded := ResolveTLS(host, deploy.Domains, deploy.CertResolver)
	if uploaded != nil {
		if err := syncTLSSecret(ctx, deploy, uploaded); err != nil {
			return nil, err
		}
	}
	return tls, nil
}

func syncTLSSecret(ctx context.Context, deploy *deployContext, uploaded *domain.Domain) error {
	secret := BuildTLSSecret(deploy.namespace(), *uploaded.Host, *uploaded.CertPem, *uploaded.KeyPem)
	patch, err := applyPatch(secret)
	if err != nil {
		return err
	}
	for attempt := 1; attempt <= tlsSecretSyncAttempts; attempt++ {
		_, err = deploy.client.Clientset.CoreV1().Secrets(deploy.namespace()).Patch(ctx, secret.Name, applyPatchType, patch, forceApply)
		if err == nil {
			return nil
		}
		slog.Warn("TLS secret sync attempt failed", "attempt", attempt, "attempts", tlsSecretSyncAttempts, "namespace", deploy.namespace(), "name", secret.Name, "domain", *uploaded.Host, "error", err.Error())
	}
	return fmt.Errorf("Failed to sync TLS secret %s/%s", deploy.namespace(), secret.Name)
}
