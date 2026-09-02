package stream

import (
	"context"
	"net/http"
	"net/url"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/util/httpstream"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/remotecommand"
)

// NewExecutor builds a remotecommand executor for the pod exec sub-resource,
// preferring the WebSocket transport and falling back to SPDY when the API
// server (or a proxy in front of it) cannot upgrade to WebSocket.
func NewExecutor(config *rest.Config, namespace, pod string, options *corev1.PodExecOptions) (remotecommand.Executor, error) {
	restClient, err := rest.RESTClientFor(execRestConfig(config))
	if err != nil {
		return nil, err
	}
	request := restClient.Post().
		Resource("pods").
		Namespace(namespace).
		Name(pod).
		SubResource("exec").
		VersionedParams(options, scheme.ParameterCodec)
	return executorFor(config, request.URL())
}

func executorFor(config *rest.Config, target *url.URL) (remotecommand.Executor, error) {
	webSocketExecutor, err := remotecommand.NewWebSocketExecutor(config, http.MethodGet, target.String())
	if err != nil {
		return nil, err
	}
	spdyExecutor, err := remotecommand.NewSPDYExecutor(config, http.MethodPost, target)
	if err != nil {
		return nil, err
	}
	return remotecommand.NewFallbackExecutor(webSocketExecutor, spdyExecutor, func(err error) bool {
		return httpstream.IsUpgradeFailure(err) || httpstream.IsHTTPSProxyError(err)
	})
}

// execRestConfig gives the config the core/v1 group version the pods resource
// lives under so RESTClientFor can build the exec URL.
func execRestConfig(config *rest.Config) *rest.Config {
	copied := rest.CopyConfig(config)
	copied.APIPath = "/api"
	copied.GroupVersion = &corev1.SchemeGroupVersion
	copied.NegotiatedSerializer = scheme.Codecs.WithoutConversion()
	return copied
}

// isDone reports whether the context has been cancelled or has expired.
func isDone(ctx context.Context) bool {
	select {
	case <-ctx.Done():
		return true
	default:
		return false
	}
}
