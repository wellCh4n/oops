import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/sandbox"

export default function SandboxDocPage() {
  return (
    <DocLayout title="沙箱">
      <DocSection title="说明">
        <DocParagraph>
          沙箱（Sandbox）用于在隔离的 Kubernetes 环境中临时执行命令。它有两种形态：
        </DocParagraph>
        <ul className="list-disc pl-5 text-sm text-muted-foreground space-y-1">
          <li>
            <b>临时执行</b>（<InlineCode>/executions</InlineCode>）：每次调用都创建一个 K8s Job，命令执行完后自动清理。
          </li>
          <li>
            <b>持久实例</b>（<InlineCode>/instances</InlineCode>）：以 StatefulSet 形态保留沙箱，可重复进入执行命令、浏览文件。
          </li>
        </ul>
        <DocParagraph>
          所有创建接口都支持设置 CPU / 内存限额与用户自定义环境变量。环境变量名必须匹配 <InlineCode>[A-Za-z_][A-Za-z0-9_]*</InlineCode>。
        </DocParagraph>
      </DocSection>

      <DocSection title="列出可用镜像">
        <Endpoint method="GET" path={`${PATH_PREFIX}/images`} summary="返回平台预置的沙箱运行时镜像列表。自定义镜像无需在此列表中。" />
      </DocSection>

      <DocSection title="临时执行">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/executions`}
          summary="一次性运行命令并返回结果（或以 SSE 流式返回）。"
        />
        <DocSubSection title="请求体 (SandboxExecutionRequest)">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true, description: "目标环境名。" },
              { name: "image", type: "string", required: true, description: "运行时镜像名，可以是平台预置或自定义。" },
              { name: "commands", type: "array", required: true, description: "顺序执行的命令列表，每个元素是一行 shell 命令字符串。" },
              { name: "timeoutSeconds", type: "int", description: "执行超时（秒），到时强制结束。" },
              { name: "ttlSecondsAfterFinished", type: "int", description: "完成后保留 Job 的秒数。" },
              { name: "cpu", type: "object", description: "CPU 资源，结构 { request, limit }，值为 K8s quantity，例如 100m。" },
              { name: "memory", type: "object", description: "内存资源，结构 { request, limit }，例如 128Mi。" },
              { name: "env", type: "object", description: "注入到容器的环境变量，键值均为字符串，例如 { \"FOO\": \"bar\" }。" },
              { name: "stream", type: "boolean", description: "true 时返回 text/event-stream，逐行推送 stdout/stderr。" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "dev",
  "image": "python:3.12-slim",
  "commands": ["python -c 'print(\\"hello\\")'"],
  "timeoutSeconds": 60,
  "env": { "FOO": "bar" }
}`}</CodeBlock>
        </DocSubSection>
        <DocSubSection title="非流式响应 (SandboxExecutionResult)">
          <FieldTable
            rows={[
              { name: "exitCode", type: "int", description: "命令最终退出码。" },
              { name: "stdout", type: "string" },
              { name: "stderr", type: "string" },
              { name: "logs", type: "string", description: "完整输出（按需返回）。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="持久实例">
        <Endpoint method="POST" path={`${PATH_PREFIX}/instances`} summary="创建一个长存沙箱（StatefulSet）。" />
        <DocSubSection title="请求体 (SandboxInstanceCreateRequest)">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true },
              { name: "name", type: "string", description: "沙箱名，环境内唯一；缺省时自动生成。" },
              { name: "image", type: "string", required: true, description: "运行时镜像。" },
              { name: "cpu", type: "object", description: "CPU 资源，结构 { request, limit }。" },
              { name: "memory", type: "object", description: "内存资源，结构 { request, limit }。" },
              { name: "env", type: "object", description: "自定义环境变量，键值均为字符串。内置 alpine-mate 镜像会在用户变量之上叠加 PUID / PGID / TZ / SUBFOLDER / TITLE 等默认变量，用户提供的同名变量会覆盖默认值。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances?environment=&image=`}
          summary="列出当前用户可见的沙箱实例。"
        />
        <DocSubSection title="查询参数">
          <FieldTable
            rows={[
              { name: "environment", type: "string", description: "可选，按环境过滤。" },
              { name: "image", type: "string", description: "可选，按镜像过滤。" },
            ]}
          />
        </DocSubSection>

        <Endpoint method="GET" path={`${PATH_PREFIX}/instances/{id}`} summary="获取指定沙箱实例的详情。" />
        <DocSubSection title="响应 (SandboxInstance)">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "name", type: "string" },
              { name: "environment", type: "string" },
              { name: "image", type: "string" },
              { name: "status", type: "string", description: "取值 PENDING / RUNNING / FAILED / TERMINATING。" },
              { name: "createdBy", type: "string" },
              { name: "createdByName", type: "string" },
              { name: "createdAt", type: "string (ISO instant)" },
              { name: "cpuRequest / cpuLimit", type: "string" },
              { name: "memoryRequest / memoryLimit", type: "string" },
            ]}
          />
        </DocSubSection>

        <DocParagraph>
          删除沙箱接口存在但 OpenAPI 不开放，需要在 UI 中执行（受 405 限制，详见 <InlineCode>认证</InlineCode> 一章）。
        </DocParagraph>
      </DocSection>

      <DocSection title="在实例内执行命令">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/exec`}
          summary="在已就绪的沙箱实例中执行一条命令。"
        />
        <DocSubSection title="请求体 (SandboxInstanceExecRequest)">
          <FieldTable
            rows={[
              { name: "command", type: "string", required: true, description: "要执行的命令（单行 shell）。" },
              { name: "timeoutSeconds", type: "int", description: "执行超时（秒）。" },
              { name: "stream", type: "boolean", description: "true 时返回 SSE 流。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="文件浏览与下载">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files?path=/`}
          summary="列出沙箱内指定目录的文件项。path 为空时使用根目录。"
        />
        <DocSubSection title="响应数组元素 (PodFileEntry)">
          <FieldTable
            rows={[
              { name: "name", type: "string" },
              { name: "path", type: "string", description: "完整路径。" },
              { name: "directory", type: "boolean" },
              { name: "size", type: "long", description: "字节数，目录为 0。" },
              { name: "modifiedAt", type: "string", description: "最后修改时间。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files/download?path=/path/to/file`}
          summary="下载沙箱内文件。响应为二进制流，不是 Result 包裹结构。"
        />
        <DocParagraph>
          单次下载受 <InlineCode>oops.pod-filesystem.max-download-size</InlineCode> 限制，超过会返回业务错误。
        </DocParagraph>
      </DocSection>
    </DocLayout>
  )
}
