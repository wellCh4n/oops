package engine

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/store"
)

// Notifier delivers a pipeline notification card to the operator's linked
// messaging account; nil when no provider is enabled.
type Notifier interface {
	SendToUser(ctx context.Context, operatorUserID, title, markdown string) error
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

// notifyPipeline mirrors PipelineNotificationListener's card: a fact list plus
// the transition detail, sent asynchronously and best-effort.
func (engine *Engine) notifyPipeline(pipeline *store.PipelineView, notificationType, detail string) {
	if engine.Notifier == nil || pipeline == nil || pipeline.OperatorID == nil || *pipeline.OperatorID == "" {
		return
	}
	operatorID := *pipeline.OperatorID
	title := "Oops 发布通知｜" + notificationTitles[notificationType]

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
		lines := []string{
			"**操作人**：" + operatorName,
			"**应用**：" + pipeline.Namespace + "/" + pipeline.ApplicationName,
			"**环境**：" + orDash(pipeline.Environment),
			"**分支**：" + branch,
			"**模式**：" + orDash(pipeline.DeployMode),
			"**流水线**：" + pipeline.ID,
			"**时间**：" + createdTime,
		}
		if detail != "" {
			lines = append(lines, "", detail)
		}
		if pipeline.Artifact != nil && *pipeline.Artifact != "" {
			lines = append(lines, "", fmt.Sprintf("镜像：%s", *pipeline.Artifact))
		}
		if err := engine.Notifier.SendToUser(ctx, operatorID, title, strings.Join(lines, "\n")); err != nil {
			log.Printf("failed to send pipeline notification for %s: %v", pipeline.ID, err)
		}
	}()
}
