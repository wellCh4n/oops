package httpapi

import (
	"context"
	"errors"

	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/store"
)

// feishuNotifier mirrors ExternalMessageService + FeishuMessageStrategy:
// resolve the operator's linked Feishu account, then send the card.
type feishuNotifier struct {
	store  *store.Store
	client *feishu.Client
}

func (notifier *feishuNotifier) SendToUser(ctx context.Context, operatorUserID, title, markdown string) error {
	account, err := notifier.store.FindExternalAccountByUser(ctx, "FEISHU", operatorUserID)
	if errors.Is(err, store.ErrNotFound) {
		return nil // no linked account — nothing to deliver, like the Java strategy
	}
	if err != nil {
		return err
	}
	if account.ProviderUserID == "" {
		return nil
	}
	return notifier.client.SendCardToUser(ctx, account.ProviderUserID, title, markdown)
}
