package engine

import (
	"context"
	"log/slog"
	"time"

	"github.com/wellch4n/oops/server/internal/store"
)

// Fact is one label/value pair rendered as a short field on the card.
type Fact struct{ Label, Value string }

// Notification mirrors ExternalUserMessage: a titled, levelled card with a
// fact grid plus optional artifact and detail sections.
type Notification struct {
	Title    string
	Level    string // SUCCESS / ERROR / WARNING / NEUTRAL / INFO
	Facts    []Fact
	Detail   string
	Artifact string
}

// Notifier delivers a notification card to a user's linked messaging
// account; nil when no provider is enabled.
type Notifier interface {
	SendToUser(ctx context.Context, userID string, message Notification) error
}

var notificationTitles = map[string]string{
	"CREATED":         "发布任务已创建",
	"BUILD_SUCCEEDED": "镜像构建完成",
	"DEPLOYING":       "发布任务部署中",
	"ROLLING_OUT":     "发布生效中",
	"SUCCEEDED":       "发布成功",
	"FAILED":          "发布失败",
	"STOPPED":         "发布已停止",
}

// notificationLevels mirrors PipelineNotificationListener.resolveLevel.
var notificationLevels = map[string]string{
	"SUCCEEDED":       "SUCCESS",
	"FAILED":          "ERROR",
	"BUILD_SUCCEEDED": "WARNING",
	"STOPPED":         "NEUTRAL",
}

// notifyPipeline mirrors PipelineNotificationListener's card: a fact grid plus
// the transition detail, sent asynchronously and best-effort.
func (engine *Engine) notifyPipeline(pipeline *store.PipelineView, notificationType, detail string) {
	if engine.Notifier == nil || pipeline == nil || pipeline.OperatorID == nil || *pipeline.OperatorID == "" {
		return
	}
	operatorID := *pipeline.OperatorID
	level := notificationLevels[notificationType]
	if level == "" {
		level = "INFO"
	}

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		operatorName := operatorID
		if user, err := engine.Store.FindUserByID(ctx, operatorID); err == nil && user.Username != "" {
			operatorName = user.Username
		}
		orDash := func(value *string) string {
			if value == nil || *value == "" {
				return "-"
			}
			return *value
		}
		branch := "-"
		if git, _ := store.DecodePublishConfig(pipeline.PublishConfig); git != nil && git.Branch != "" {
			branch = git.Branch
		}
		createdTime := "-"
		if pipeline.CreatedTime != nil {
			createdTime = pipeline.CreatedTime.In(time.Local).Format("2006-01-02 15:04:05")
		}
		message := Notification{
			Title: "Oops 发布通知｜" + notificationTitles[notificationType],
			Level: level,
			Facts: []Fact{
				{"操作人", operatorName},
				{"应用", pipeline.Namespace + "/" + pipeline.ApplicationName},
				{"环境", orDash(pipeline.Environment)},
				{"分支", branch},
				{"模式", orDash(pipeline.DeployMode)},
				{"流水线", pipeline.ID},
				{"时间", createdTime},
			},
			Detail: detail,
		}
		if pipeline.Artifact != nil {
			message.Artifact = *pipeline.Artifact
		}
		if err := engine.Notifier.SendToUser(ctx, operatorID, message); err != nil {
			slog.Error("failed to send pipeline notification", "pipeline", pipeline.ID, "error", err)
		}
	}()
}
