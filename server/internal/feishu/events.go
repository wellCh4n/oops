package feishu

import (
	"context"
	"errors"
	"log"

	"github.com/larksuite/oapi-sdk-go/v3/event/dispatcher"
	larkcontact "github.com/larksuite/oapi-sdk-go/v3/service/contact/v3"
	larkws "github.com/larksuite/oapi-sdk-go/v3/ws"

	"github.com/wellch4n/oops/server/internal/store"
)

// RunEventClient dials the Lark event long connection and disables the OOPS
// account of anyone the directory reports as removed, mirroring
// FeishuEventClient + ExternalUserDeactivatedListener.
//
// Transport is the SDK's outbound WebSocket (no public endpoint, no
// verification token — the connection authenticates with the app credentials),
// and reconnection is left entirely to the SDK. Delivery is at least once with
// no de-duplication: disabling an already-disabled account is a no-op.
// The SDK acknowledges an event even when the handler fails, so a failure is
// logged with the account it was about — the log is the only trace.
func RunEventClient(ctx context.Context, appID, appSecret string, st *store.Store) {
	eventDispatcher := dispatcher.NewEventDispatcher("", "").
		OnP2UserDeletedV3(func(ctx context.Context, event *larkcontact.P2UserDeletedV3) error {
			providerUserID, email := "", ""
			if event.Event != nil && event.Event.Object != nil {
				if event.Event.Object.UserId != nil {
					providerUserID = *event.Event.Object.UserId
				}
				if event.Event.Object.EnterpriseEmail != nil && *event.Event.Object.EnterpriseEmail != "" {
					email = *event.Event.Object.EnterpriseEmail
				} else if event.Event.Object.Email != nil {
					email = *event.Event.Object.Email
				}
			}
			// Every event is logged on receipt and outcome: resignations are
			// rare, and without these lines an event matching no account would
			// leave no trace of having arrived.
			log.Printf("feishu reported user %s as removed from the organisation", providerUserID)

			userID := ""
			if account, err := st.FindExternalAccountByProviderUser(ctx, "FEISHU", providerUserID); err == nil {
				userID = account.UserID
			} else if errors.Is(err, store.ErrNotFound) && email != "" {
				if user, err := st.FindUserByEmail(ctx, email); err == nil {
					userID = user.ID
				}
			}
			if userID == "" {
				// Not an error: plenty of people in the directory never signed in to OOPS.
				log.Printf("feishu user %s left the organisation but has no linked OOPS account", providerUserID)
				return nil
			}
			disabled, err := st.DeactivateUser(ctx, userID)
			switch {
			case err != nil:
				// Nothing will retry this: name the account an admin has to disable by hand.
				log.Printf("failed to disable OOPS user %s after feishu reported %s as removed: %v", userID, providerUserID, err)
			case disabled:
				log.Printf("disabled OOPS user %s after feishu reported the account as removed", userID)
			default:
				log.Printf("OOPS user %s was left as is after feishu reported the account as removed", userID)
			}
			return nil
		})

	client := larkws.NewClient(appID, appSecret, larkws.WithEventHandler(eventDispatcher), larkws.WithAutoReconnect(true))
	// Started from a goroutine so an unreachable Feishu cannot hold up startup.
	go func() {
		// Start only returns errors no retry fixes (bad credentials);
		// ordinary drops are retried internally via autoReconnect.
		err := client.Start(ctx)
		log.Printf("feishu event long connection stopped: %v", err)
	}()
}
