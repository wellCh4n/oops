// Package prometheus reads pod resource usage out of whatever
// Prometheus-compatible backend the cluster already runs. OOPS stores no metric
// history of its own: the usage charts and the resource alerts are both just
// queries against that backend.
//
// The backend is reached through the API server's service proxy, so an
// in-cluster ClusterIP needs no ingress and no second credential — only
// `services/proxy` on the environment's token.
package prometheus

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	apierrors "k8s.io/apimachinery/pkg/api/errors"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
)

// ErrNotAvailable is what every endpoint reports when no backend answers. The
// drawer renders it as a setup prompt rather than an empty chart, which is why
// it has to be distinguishable from "this application has no pods".
const ErrNotAvailable = "MONITORING_NOT_AVAILABLE"

// safeName is the Kubernetes name shape. Namespaces and application names are
// interpolated into PromQL label matchers, so anything else is refused rather
// than escaped.
var safeName = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`)

// PodSelector matches one application's containers.
//
// Pods are matched by StatefulSet naming rather than by the oops.app.name label,
// because kubelet and cAdvisor metrics carry no pod labels at all. PromQL
// matchers are fully anchored, so `foo-[0-9]+` cannot also match `foo-bar-0`.
func PodSelector(namespace, applicationName string) (string, error) {
	if err := requireSafeName(namespace, "namespace"); err != nil {
		return "", err
	}
	if err := requireSafeName(applicationName, "application name"); err != nil {
		return "", err
	}
	return fmt.Sprintf(`namespace=%q,pod=~%q,container!="",container!="POD"`,
		namespace, applicationName+"-[0-9]+"), nil
}

func requireSafeName(value, what string) error {
	if !safeName.MatchString(value) {
		return domain.Bizf("Invalid %s: %s", what, value)
	}
	return nil
}

// Client queries a backend through the API server proxy.
type Client struct {
	pool    *k8s.Pool
	backend config.MetricsBackend
}

func NewClient(pool *k8s.Pool, backend config.MetricsBackend) *Client {
	return &Client{pool: pool, backend: backend}
}

// Configured reports whether a backend is addressable at all.
func (c *Client) Configured() bool { return c.backend.Configured() }

// QueryRange runs a query_range and returns the raw response body.
func (c *Client) QueryRange(ctx context.Context, environment *domain.Environment, query string, startSeconds, endSeconds int64, stepSeconds int) ([]byte, error) {
	path := fmt.Sprintf("query_range?query=%s&start=%d&end=%d&step=%d",
		url.QueryEscape(query), startSeconds, endSeconds, stepSeconds)
	return c.get(ctx, environment, path)
}

// Query runs an instant query and returns the raw response body.
func (c *Client) Query(ctx context.Context, environment *domain.Environment, query string) ([]byte, error) {
	return c.get(ctx, environment, "query?query="+url.QueryEscape(query))
}

func (c *Client) get(ctx context.Context, environment *domain.Environment, apiPath string) ([]byte, error) {
	if !c.backend.Configured() {
		return nil, domain.Biz(ErrNotAvailable)
	}
	client, err := c.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return nil, err
	}
	// The whole URI is set at once rather than built segment by segment: the
	// builder would escape the already-escaped PromQL in the query string a
	// second time.
	body, err := client.Clientset.CoreV1().RESTClient().Get().
		RequestURI(proxyURI(c.backend.Namespace, c.backend.ServiceName, c.backend.Port, apiPath)).
		DoRaw(ctx)
	if err != nil {
		// A 404 is the backend simply not being there, which is a setup state
		// rather than a failure worth an error page.
		if apierrors.IsNotFound(err) {
			slog.Info("no monitoring backend", "backend", c.backend.Describe(), "environment", environment.Name)
			return nil, domain.Biz(ErrNotAvailable)
		}
		slog.Warn("monitoring query failed", "environment", environment.Name, "backend", c.backend.Describe(), "error", err)
		return nil, domain.Bizf("Cannot reach the monitoring backend at %s: %s", c.backend.Describe(), err.Error())
	}
	return body, nil
}

// proxyURI builds the whole request URI, because the query string has to survive
// intact and the builder would otherwise escape it a second time.
func proxyURI(namespace, serviceName string, port int, apiPath string) string {
	return fmt.Sprintf("/api/v1/namespaces/%s/services/%s:%d/proxy/api/v1/%s",
		namespace, serviceName, port, apiPath)
}

// Sample is one reading: an epoch second and a value.
type Sample struct {
	TimestampSeconds int64
	Value            float64
}

// ParseMatrix reads a query_range matrix into pod -> samples.
func ParseMatrix(body []byte) (map[string][]Sample, error) {
	var response struct {
		Status string `json:"status"`
		Error  string `json:"error"`
		Data   struct {
			Result []struct {
				Metric map[string]string   `json:"metric"`
				Values [][]json.RawMessage `json:"values"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, domain.Biz("Monitoring backend returned a malformed response")
	}
	if response.Status != "success" {
		reason := response.Error
		if reason == "" {
			reason = "unknown error"
		}
		return nil, domain.Bizf("Monitoring query rejected: %s", reason)
	}
	byPod := map[string][]Sample{}
	for _, series := range response.Data.Result {
		podName := series.Metric["pod"]
		if strings.TrimSpace(podName) == "" {
			continue
		}
		samples := make([]Sample, 0, len(series.Values))
		for _, pair := range series.Values {
			if len(pair) < 2 {
				continue
			}
			timestamp, ok := parseNumber(pair[0])
			if !ok {
				continue
			}
			// Values arrive as strings and may legitimately be NaN when the pod
			// was not running at that step.
			value, ok := parseNumber(pair[1])
			if !ok {
				continue
			}
			samples = append(samples, Sample{TimestampSeconds: int64(timestamp), Value: value})
		}
		if len(samples) > 0 {
			byPod[podName] = samples
		}
	}
	return byPod, nil
}

// ParseVector reads an instant query into pod -> value.
func ParseVector(body []byte) (map[string]float64, error) {
	var response struct {
		Status string `json:"status"`
		Error  string `json:"error"`
		Data   struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
				Value  []json.RawMessage `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, domain.Biz("Monitoring backend returned a malformed response")
	}
	if response.Status != "success" {
		reason := response.Error
		if reason == "" {
			reason = "unknown error"
		}
		return nil, domain.Bizf("Monitoring query rejected: %s", reason)
	}
	byPod := map[string]float64{}
	for _, series := range response.Data.Result {
		podName := series.Metric["pod"]
		if strings.TrimSpace(podName) == "" || len(series.Value) < 2 {
			continue
		}
		value, ok := parseNumber(series.Value[1])
		if !ok {
			continue
		}
		byPod[podName] = value
	}
	return byPod, nil
}

// parseNumber reads a Prometheus scalar, which may be a JSON number or a JSON
// string. NaN and Inf are reported as absent.
func parseNumber(raw json.RawMessage) (float64, bool) {
	text := strings.Trim(string(raw), `"`)
	value, err := strconv.ParseFloat(text, 64)
	if err != nil || value != value { // NaN never equals itself
		return 0, false
	}
	return value, true
}
