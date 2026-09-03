// Package feishu talks to Feishu (Lark) over its REST API directly rather than
// pulling in the vendor SDK. Two things need it: the OAuth login flow, and the
// notifications a pipeline sends its operator.
package feishu

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

const baseURL = "https://open.feishu.cn/open-apis"

// tokenSkew renews a little before expiry so a call never races the token it is
// being made with.
const tokenSkew = 5 * time.Minute

// Feishu issues two app-level tokens and they are not interchangeable: the
// messaging API wants the tenant token, the authentication API wants the app
// token. Each is cached separately.
const (
	tenantTokenPath = "/auth/v3/tenant_access_token/internal"
	appTokenPath    = "/auth/v3/app_access_token/internal"
)

// Client holds the app credentials and the cached tokens.
type Client struct {
	appID     string
	appSecret string
	http      *http.Client

	mu     sync.Mutex
	tokens map[string]cachedToken
}

type cachedToken struct {
	value   string
	expires time.Time
}

// NewClient builds a client. Callers only construct one when Feishu is enabled.
func NewClient(appID, appSecret string) *Client {
	return &Client{
		appID: appID, appSecret: appSecret,
		http:   &http.Client{Timeout: 10 * time.Second},
		tokens: map[string]cachedToken{},
	}
}

// AppID is needed to build the authorize URL.
func (c *Client) AppID() string { return c.appID }

// token returns a cached app-level token, fetching one when it is missing or
// close to expiry. path selects which of the two kinds.
func (c *Client) token(ctx context.Context, path string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if cached, found := c.tokens[path]; found && cached.value != "" && time.Now().Before(cached.expires) {
		return cached.value, nil
	}
	payload, err := json.Marshal(map[string]string{"app_id": c.appID, "app_secret": c.appSecret})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", "application/json; charset=utf-8")

	// Both endpoints return their token at the envelope's top level, under
	// different names, so both are read and whichever arrived is used.
	var response struct {
		TenantAccessToken string `json:"tenant_access_token"`
		AppAccessToken    string `json:"app_access_token"`
		Expire            int    `json:"expire"`
	}
	if err := c.do(request, &response); err != nil {
		return "", err
	}
	value := response.TenantAccessToken
	if value == "" {
		value = response.AppAccessToken
	}
	if value == "" {
		return "", fmt.Errorf("feishu returned no access token from %s", path)
	}
	c.tokens[path] = cachedToken{
		value:   value,
		expires: time.Now().Add(time.Duration(response.Expire) * time.Second).Add(-tokenSkew),
	}
	return value, nil
}

// postJSON sends a JSON body with a bearer token and decodes the response.
func (c *Client) postJSON(ctx context.Context, path, bearer string, body, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json; charset=utf-8")
	request.Header.Set("Authorization", "Bearer "+bearer)
	return c.do(request, out)
}

// get sends a GET with a bearer token and decodes the response.
func (c *Client) get(ctx context.Context, path, bearer string, out any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, baseURL+path, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+bearer)
	return c.do(request, out)
}

// do sends the request and checks Feishu's own status code, which travels in
// the body: the transport reports 200 even for a rejected call.
func (c *Client) do(request *http.Request, out any) error {
	response, err := c.http.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	body := new(bytes.Buffer)
	if _, err := body.ReadFrom(response.Body); err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("feishu: HTTP %d: %s", response.StatusCode, strings.TrimSpace(body.String()))
	}
	var envelope struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
	}
	if err := json.Unmarshal(body.Bytes(), &envelope); err != nil {
		return err
	}
	if envelope.Code != 0 {
		return fmt.Errorf("feishu: code=%d, msg=%s", envelope.Code, envelope.Msg)
	}
	if out != nil {
		return json.Unmarshal(body.Bytes(), out)
	}
	return nil
}
