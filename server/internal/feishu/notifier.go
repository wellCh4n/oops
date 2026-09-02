// Package feishu delivers notifications to Feishu (Lark). It speaks the two REST
// calls it needs directly rather than pulling in the vendor SDK: a tenant access
// token, and one interactive-card message.
package feishu

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

const (
	tokenURL   = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
	messageURL = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=user_id"
	// tokenSkew renews a little before expiry so a message never races the
	// token it is being sent with.
	tokenSkew = 5 * time.Minute
)

// Notifier sends card messages to the Feishu account linked to an OOPS user.
type Notifier struct {
	appID     string
	appSecret string
	store     *store.Store
	client    *http.Client

	mu          sync.Mutex
	token       string
	tokenExpiry time.Time
}

// New builds a notifier. Callers only construct one when Feishu is enabled.
func New(appID, appSecret string, db *store.Store) *Notifier {
	return &Notifier{
		appID: appID, appSecret: appSecret, store: db,
		client: &http.Client{Timeout: 10 * time.Second},
	}
}

// Notify sends one message. It never returns an error: a notification that
// could not be delivered must not fail the deploy that triggered it, so a
// failure is logged and nothing else.
func (n *Notifier) Notify(ctx context.Context, userID, title, body string) {
	if strings.TrimSpace(userID) == "" || strings.TrimSpace(title) == "" {
		return
	}
	account, err := n.store.ExternalAccounts().FindByProviderAndUserID(ctx, domain.ProviderFeishu, userID)
	if err != nil || account == nil || domain.IsBlank(account.ProviderUserID) {
		// Not every OOPS user has linked a Feishu account; that is ordinary.
		return
	}
	if err := n.send(ctx, *account.ProviderUserID, title, body); err != nil {
		slog.Warn("failed to send a Feishu notification", "userId", userID, "error", err)
	}
}

func (n *Notifier) send(ctx context.Context, receiveID, title, body string) error {
	token, err := n.accessToken(ctx)
	if err != nil {
		return err
	}
	card, err := json.Marshal(buildCard(title, body))
	if err != nil {
		return err
	}
	payload, err := json.Marshal(map[string]string{
		"receive_id": receiveID,
		"msg_type":   "interactive",
		"content":    string(card),
		// Feishu de-duplicates on this, so a retried request cannot double-post.
		"uuid": domain.NewID(),
	})
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, messageURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json; charset=utf-8")
	request.Header.Set("Authorization", "Bearer "+token)
	return n.do(request, nil)
}

// buildCard renders the message as an interactive card, which is what the Java
// backend sent and what the Feishu client renders legibly.
func buildCard(title, body string) map[string]any {
	elements := []map[string]any{}
	if strings.TrimSpace(body) != "" {
		elements = append(elements, map[string]any{
			"tag":  "div",
			"text": map[string]string{"tag": "lark_md", "content": body},
		})
	}
	return map[string]any{
		"header": map[string]any{
			"template": "blue",
			"title":    map[string]string{"tag": "plain_text", "content": title},
		},
		"elements": elements,
	}
}

// accessToken returns a cached tenant token, renewing it shortly before expiry.
func (n *Notifier) accessToken(ctx context.Context) (string, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.token != "" && time.Now().Before(n.tokenExpiry) {
		return n.token, nil
	}
	payload, err := json.Marshal(map[string]string{"app_id": n.appID, "app_secret": n.appSecret})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, tokenURL, bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", "application/json; charset=utf-8")

	var response struct {
		TenantAccessToken string `json:"tenant_access_token"`
		Expire            int    `json:"expire"`
	}
	if err := n.do(request, &response); err != nil {
		return "", err
	}
	if response.TenantAccessToken == "" {
		return "", fmt.Errorf("feishu returned no tenant access token")
	}
	n.token = response.TenantAccessToken
	n.tokenExpiry = time.Now().Add(time.Duration(response.Expire) * time.Second).Add(-tokenSkew)
	return n.token, nil
}

// do sends the request and checks Feishu's own status code, which travels in the
// body: the transport reports 200 even for a rejected call.
func (n *Notifier) do(request *http.Request, out any) error {
	response, err := n.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	var envelope struct {
		Code int             `json:"code"`
		Msg  string          `json:"msg"`
		Data json.RawMessage `json:"data"`
	}
	decoded, err := readEnvelope(response, &envelope)
	if err != nil {
		return err
	}
	if envelope.Code != 0 {
		return fmt.Errorf("feishu: code=%d, msg=%s", envelope.Code, envelope.Msg)
	}
	if out != nil {
		// The token lives at the envelope's top level, not inside data, so the
		// whole body is decoded again rather than just data.
		return json.Unmarshal(decoded, out)
	}
	return nil
}

func readEnvelope(response *http.Response, envelope any) ([]byte, error) {
	body := new(bytes.Buffer)
	if _, err := body.ReadFrom(response.Body); err != nil {
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("feishu: HTTP %d: %s", response.StatusCode, strings.TrimSpace(body.String()))
	}
	if err := json.Unmarshal(body.Bytes(), envelope); err != nil {
		return nil, err
	}
	return body.Bytes(), nil
}
