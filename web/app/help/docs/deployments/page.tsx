import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications/{name}/deployments"

export default function DeploymentsDocPage() {
  return (
    <DocLayout title="部署">
      <DocSection title="说明">
        <DocParagraph>
          部署是触发流水线的入口。请求体中的 <InlineCode>strategy</InlineCode> 字段决定本次构建从何处取得源码：Git 仓库或 ZIP 包。
          使用 ZIP 源时需要先调用上传接口拿到对象存储的预签名 URL，将文件上传后再发起部署。
        </DocParagraph>
        <DocParagraph>
          OOPS 不允许同一个应用并发部署，若该应用已有处于 <InlineCode>RUNNING</InlineCode> 或 <InlineCode>DEPLOYING</InlineCode> 的流水线，调用会被拒绝。
        </DocParagraph>
      </DocSection>

      <DocSection title="申请源码上传地址（仅 ZIP）">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/source-upload`}
          summary="返回一个用于上传 ZIP 源码包的预签名 URL，仅在对象存储已配置时可用。"
        />
        <DocSubSection title="请求体 (ObjectStorageUploadCommand)">
          <FieldTable
            rows={[
              { name: "fileName", type: "string", required: true, description: "原始文件名，用于在对象存储中拼接 key。" },
              { name: "fileSize", type: "long", required: true, description: "文件字节数。超过 oops.object-storage.max-file-size 会被拒绝。" },
              { name: "contentType", type: "string", description: "上传时使用的 Content-Type，例如 application/zip。" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="响应 (ObjectStorageUploadResult)">
          <FieldTable
            rows={[
              { name: "objectKey", type: "string", description: "对象存储中的 key，部署时填入 strategy.repository。" },
              { name: "objectUrl", type: "string", description: "对象的最终下载地址（仅展示用途）。" },
              { name: "uploadUrl", type: "string", description: "预签名 PUT URL，客户端用其上传文件。" },
              { name: "headers", type: "object", description: "上传时需要附带的请求头，键值均为字符串。" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="上传示例">
          <CodeBlock language="bash">{`curl -X PUT "$UPLOAD_URL" \\
  -H "Content-Type: application/zip" \\
  --data-binary @./build.zip`}</CodeBlock>
        </DocSubSection>
      </DocSection>

      <DocSection title="触发部署">
        <Endpoint method="POST" path={PATH_PREFIX} summary="创建一条流水线并立即开始构建。响应 data 为新流水线的 id。" />
        <DocSubSection title="请求体 (DeployCommand)">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true, description: "目标环境名，需在应用绑定的环境中。" },
              { name: "deployMode", type: "string", required: true, description: "取值 IMMEDIATE 或 MANUAL。MANUAL 模式下构建结束后需手动调用 deploy 接口。" },
              { name: "strategy", type: "object", required: true, description: "源码来源。带类型字段 type，见下方两种形态。" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="Git 源">
          <FieldTable
            rows={[
              { name: "strategy.type", type: "string", required: true, description: "固定为 GIT。" },
              { name: "strategy.branch", type: "string", required: true, description: "分支名或 commit。" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "prod",
  "deployMode": "IMMEDIATE",
  "strategy": { "type": "GIT", "branch": "main" }
}`}</CodeBlock>
        </DocSubSection>
        <DocSubSection title="ZIP 源">
          <FieldTable
            rows={[
              { name: "strategy.type", type: "string", required: true, description: "固定为 ZIP。" },
              { name: "strategy.repository", type: "string", required: true, description: "上一步返回的 objectKey。" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "prod",
  "deployMode": "MANUAL",
  "strategy": { "type": "ZIP", "repository": "uploads/build-2025-12-01.zip" }
}`}</CodeBlock>
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
