// Package feishu implements the Lark open-platform calls the Java SDK made:
// OAuth login and interactive-card messages. The inbound event long connection
// (contact.user.deleted_v3 → account deactivation) uses the SDK's proprietary
// WebSocket framing and is not ported yet.
package feishu

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

type Client struct {
	AppID     string
	AppSecret string
	http      *http.Client
}

func NewClient(appID, appSecret string) *Client {
	return &Client{AppID: appID, AppSecret: appSecret, http: &http.Client{Timeout: 15 * time.Second}}
}

// LoginURL mirrors FeishuAuthStrategy.getLoginUrl.
func (client *Client) LoginURL(redirectURI string) string {
	return "https://open.feishu.cn/open-apis/authen/v1/authorize" +
		"?app_id=" + client.AppID +
		"&redirect_uri=" + url.QueryEscape(redirectURI) +
		"&state=feishu"
}

func (client *Client) postJSON(ctx context.Context, endpoint, bearer string, body any, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		request.Header.Set("Authorization", "Bearer "+bearer)
	}
	response, err := client.http.Do(request)
	if err != nil {
		return fmt.Errorf("calling Feishu API: %w", err)
	}
	defer response.Body.Close()
	return json.NewDecoder(response.Body).Decode(out)
}

func (client *Client) appAccessToken(ctx context.Context) (string, error) {
	var response struct {
		Code           int    `json:"code"`
		Msg            string `json:"msg"`
		AppAccessToken string `json:"app_access_token"`
	}
	err := client.postJSON(ctx, "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal", "",
		map[string]string{"app_id": client.AppID, "app_secret": client.AppSecret}, &response)
	if err != nil {
		return "", err
	}
	if response.Code != 0 {
		return "", fmt.Errorf("failed to get app access token: %s, code: %d", response.Msg, response.Code)
	}
	return response.AppAccessToken, nil
}

// UserInfo is the subset of the authen user_info payload OOPS reads.
type UserInfo struct {
	UserID          string
	Name            string
	Email           string
	EnterpriseEmail string
}

// Authenticate exchanges the OAuth code for the Feishu user profile,
// mirroring FeishuAuthStrategy's token + user_info calls.
func (client *Client) Authenticate(ctx context.Context, code string) (*UserInfo, error) {
	appToken, err := client.appAccessToken(ctx)
	if err != nil {
		return nil, err
	}
	var tokenResponse struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			AccessToken string `json:"access_token"`
		} `json:"data"`
	}
	err = client.postJSON(ctx, "https://open.feishu.cn/open-apis/authen/v1/access_token", appToken,
		map[string]string{"grant_type": "authorization_code", "code": code}, &tokenResponse)
	if err != nil {
		return nil, err
	}
	if tokenResponse.Code != 0 {
		return nil, fmt.Errorf("failed to get access token: %s, code: %d", tokenResponse.Msg, tokenResponse.Code)
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://open.feishu.cn/open-apis/authen/v1/user_info", nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+tokenResponse.Data.AccessToken)
	response, err := client.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("calling Feishu API: %w", err)
	}
	defer response.Body.Close()
	var infoResponse struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			UserID          string `json:"user_id"`
			Name            string `json:"name"`
			Email           string `json:"email"`
			EnterpriseEmail string `json:"enterprise_email"`
		} `json:"data"`
	}
	if err := json.NewDecoder(response.Body).Decode(&infoResponse); err != nil {
		return nil, err
	}
	if infoResponse.Code != 0 {
		return nil, fmt.Errorf("failed to get user info: %s, code: %d", infoResponse.Msg, infoResponse.Code)
	}
	return &UserInfo{
		UserID:          infoResponse.Data.UserID,
		Name:            infoResponse.Data.Name,
		Email:           infoResponse.Data.Email,
		EnterpriseEmail: infoResponse.Data.EnterpriseEmail,
	}, nil
}

// CardFact is one short field on the card's fact grid.
type CardFact struct{ Label, Value string }

// SendCardToUser mirrors FeishuMessageStrategy.buildCard: a colored header
// (template by message level), a two-column fact grid, and optional artifact
// and detail sections.
func (client *Client) SendCardToUser(ctx context.Context, providerUserID, title, template string, facts []CardFact, artifact, detail string) error {
	appToken, err := client.appAccessToken(ctx)
	if err != nil {
		return err
	}
	markdown := func(content string) map[string]any {
		return map[string]any{"tag": "lark_md", "content": content}
	}
	fields := make([]any, 0, len(facts))
	for _, fact := range facts {
		value := fact.Value
		if value == "" {
			value = "-"
		}
		fields = append(fields, map[string]any{
			"is_short": true,
			"text":     markdown("**" + fact.Label + "**\n" + value),
		})
	}
	elements := []any{map[string]any{"tag": "div", "fields": fields}}
	if artifact != "" {
		elements = append(elements, map[string]any{"tag": "div", "text": markdown("**制品**\n" + artifact)})
	}
	if detail != "" {
		elements = append(elements, map[string]any{"tag": "div", "text": markdown("**说明**\n" + detail)})
	}
	card := map[string]any{
		"config": map[string]any{"wide_screen_mode": true},
		"header": map[string]any{
			"template": template,
			"title":    map[string]any{"tag": "plain_text", "content": title},
		},
		"elements": elements,
	}
	content, err := json.Marshal(card)
	if err != nil {
		return err
	}
	var response struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
	}
	err = client.postJSON(ctx,
		"https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=user_id", appToken,
		map[string]any{
			"receive_id": providerUserID,
			"msg_type":   "interactive",
			"content":    string(content),
		}, &response)
	if err != nil {
		return err
	}
	if response.Code != 0 {
		return fmt.Errorf("failed to send message: code=%d, msg=%s", response.Code, response.Msg)
	}
	return nil
}
