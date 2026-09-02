// Package k8s holds every Kubernetes adapter. This file is the shared client
// factory and pool; everything else in the package builds on it.
package k8s

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	metricsclient "k8s.io/metrics/pkg/client/clientset/versioned"
)

const (
	connectTimeout = 5 * time.Second
	requestTimeout = 10 * time.Second
	poolIdleExpiry = 10 * time.Minute
)

// Client bundles the typed, dynamic and metrics clients for one API server.
type Client struct {
	Config    *rest.Config
	Clientset kubernetes.Interface
	Dynamic   dynamic.Interface
	Metrics   metricsclient.Interface
}

// RestConfig mirrors KubernetesClients.from: bearer token only, no CA, TLS
// not verified, 10s request timeout, no retries.
func RestConfig(apiServer *domain.KubernetesApiServer) (*rest.Config, error) {
	if apiServer == nil || domain.Deref(apiServer.URL) == "" {
		return nil, errors.New("kubernetes api server is not configured")
	}
	cfg := &rest.Config{
		Host:        strings.TrimRight(*apiServer.URL, "/"),
		BearerToken: domain.Deref(apiServer.Token),
		Timeout:     requestTimeout,
		TLSClientConfig: rest.TLSClientConfig{
			Insecure: true,
		},
	}
	cfg.Dial = (&netDialer{timeout: connectTimeout}).DialContext
	return cfg, nil
}

// New builds a fresh Client. Callers that only need a short-lived client
// (deploy task, validation) use this directly; long-lived gateways use Pool.
func New(apiServer *domain.KubernetesApiServer) (*Client, error) {
	cfg, err := RestConfig(apiServer)
	if err != nil {
		return nil, err
	}
	return NewFromConfig(cfg)
}

// NewFromConfig builds a Client from a rest.Config.
func NewFromConfig(cfg *rest.Config) (*Client, error) {
	clientset, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	dyn, err := dynamic.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	metrics, err := metricsclient.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	return &Client{Config: cfg, Clientset: clientset, Dynamic: dyn, Metrics: metrics}, nil
}

// StreamingClient returns a Client whose HTTP timeout is disabled, for
// watches, log follows and exec sessions that legitimately outlive 10s.
func StreamingClient(apiServer *domain.KubernetesApiServer) (*Client, error) {
	cfg, err := RestConfig(apiServer)
	if err != nil {
		return nil, err
	}
	cfg.Timeout = 0
	return NewFromConfig(cfg)
}

// Pool caches clients keyed by "url|token", dropping entries idle for 10 min.
type Pool struct {
	mu      sync.Mutex
	entries map[string]*poolEntry
}

type poolEntry struct {
	client   *Client
	lastUsed time.Time
}

func NewPool() *Pool {
	p := &Pool{entries: map[string]*poolEntry{}}
	go p.sweep()
	return p
}

// Get returns the pooled client for the api server, creating it on demand.
func (p *Pool) Get(apiServer *domain.KubernetesApiServer) (*Client, error) {
	if apiServer == nil {
		return nil, errors.New("kubernetes api server is not configured")
	}
	key := domain.Deref(apiServer.URL) + "|" + domain.Deref(apiServer.Token)
	p.mu.Lock()
	defer p.mu.Unlock()
	if entry, ok := p.entries[key]; ok {
		entry.lastUsed = time.Now()
		return entry.client, nil
	}
	client, err := New(apiServer)
	if err != nil {
		return nil, err
	}
	p.entries[key] = &poolEntry{client: client, lastUsed: time.Now()}
	return client, nil
}

func (p *Pool) sweep() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		p.mu.Lock()
		for key, entry := range p.entries {
			if time.Since(entry.lastUsed) > poolIdleExpiry {
				delete(p.entries, key)
			}
		}
		p.mu.Unlock()
	}
}

// TranslateError mirrors KubernetesExceptionTranslationAspect: API errors
// become BizErrors with the same messages the Java backend produced. Non-API
// errors are returned unchanged.
func TranslateError(err error) error {
	if err == nil {
		return nil
	}
	if domain.IsBiz(err) {
		return err
	}
	var status *apierrors.StatusError
	if errors.As(err, &status) {
		code := int(status.ErrStatus.Code)
		switch {
		case code <= 0:
			return domain.BizWrap("Failed to reach Kubernetes API server: "+rootCause(err), err)
		case code == http.StatusUnauthorized || code == http.StatusForbidden:
			return domain.BizWrap(fmt.Sprintf("Kubernetes API authentication failed (HTTP %d). Please verify the environment's API token.", code), err)
		default:
			return domain.BizWrap(fmt.Sprintf("Kubernetes API error (HTTP %d): %s", code, err.Error()), err)
		}
	}
	if isNetworkError(err) {
		return domain.BizWrap("Failed to reach Kubernetes API server: "+rootCause(err), err)
	}
	return err
}

func rootCause(err error) string {
	for {
		next := errors.Unwrap(err)
		if next == nil {
			return err.Error()
		}
		err = next
	}
}

func isNetworkError(err error) bool {
	msg := err.Error()
	for _, marker := range []string{"connection refused", "no such host", "i/o timeout", "dial tcp", "EOF", "TLS handshake", "context deadline exceeded", "no route to host", "network is unreachable"} {
		if strings.Contains(msg, marker) {
			return true
		}
	}
	return false
}

// IsNotFound reports a 404 from the API server.
func IsNotFound(err error) bool { return apierrors.IsNotFound(err) }

// IsAlreadyExists reports a 409 from the API server.
func IsAlreadyExists(err error) bool { return apierrors.IsAlreadyExists(err) }
