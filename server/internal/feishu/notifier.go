package feishu

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

// messagePath sends to a user by their Feishu user id, which is what the
// external_account row stores.
const messagePath = "/im/v1/messages?receive_id_type=user_id"

// Notifier sends card messages to the Feishu account linked to an OOPS user.
type Notifier struct {
	client *Client
	store  *store.Store
}

// NewNotifier builds a notifier over an existing client.
func NewNotifier(client *Client, db *store.Store) *Notifier {
	return &Notifier{client: client, store: db}
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
	// Messaging authenticates as the tenant, unlike the login flow.
	token, err := n.client.token(ctx, tenantTokenPath)
	if err != nil {
		return err
	}
	card, err := json.Marshal(buildCard(title, body))
	if err != nil {
		return err
	}
	return n.client.postJSON(ctx, messagePath, token, map[string]string{
		"receive_id": receiveID,
		"msg_type":   "interactive",
		"content":    string(card),
		// Feishu de-duplicates on this, so a retried request cannot double-post.
		"uuid": domain.NewID(),
	}, nil)
}

// buildCard renders the message as an interactive card, which is what the
// Feishu client renders legibly.
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
