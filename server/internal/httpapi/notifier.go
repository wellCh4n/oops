package httpapi

import (
	"context"
	"errors"

	"github.com/wellch4n/oops/server/internal/engine"
	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/store"
)

// feishuNotifier mirrors ExternalMessageService + FeishuMessageStrategy:
// resolve the user's linked Feishu account, then send the interactive card.
type feishuNotifier struct {
	store  *store.Store
	client *feishu.Client
}

// cardTemplates mirrors FeishuMessageStrategy.resolveTemplate.
var cardTemplates = map[string]string{
	"SUCCESS": "green",
	"ERROR":   "red",
	"WARNING": "orange",
	"NEUTRAL": "grey",
}

func (notifier *feishuNotifier) SendToUser(ctx context.Context, userID string, message engine.Notification) error {
	account, err := notifier.store.FindExternalAccountByUser(ctx, "FEISHU", userID)
	if errors.Is(err, store.ErrNotFound) {
		return nil // no linked account — nothing to deliver, like the Java strategy
	}
	if err != nil {
		return err
	}
	if account.ProviderUserID == "" {
		return nil
	}
	template := cardTemplates[message.Level]
	if template == "" {
		template = "blue"
	}
	facts := make([]feishu.CardFact, 0, len(message.Facts))
	for _, fact := range message.Facts {
		facts = append(facts, feishu.CardFact{Label: fact.Label, Value: fact.Value})
	}
	return notifier.client.SendCardToUser(ctx, account.ProviderUserID, message.Title, template, facts, message.Artifact, message.Detail)
}
