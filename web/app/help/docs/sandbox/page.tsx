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
              { name: "useDefaultKeepalive", type: "boolean", description: "是否使用默认保活命令（sleep infinity）。缺省为 true：会覆盖镜像的 ENTRYPOINT/CMD。设为 false 则保留镜像自身的启动入口，适合 Dockerfile 已定义可长驻进程的场景。" },
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

        <Endpoint
          method="DELETE"
          path={`${PATH_PREFIX}/instances/{id}`}
          summary="销毁指定的持久沙箱实例，连带删除其 StatefulSet、Service。响应 data 为 null。"
        />
        <DocParagraph>
          删除是沙箱生命周期的一部分，OpenAPI 对该路径开放 <InlineCode>DELETE</InlineCode>，其他资源仍受 405 限制（详见 <InlineCode>认证</InlineCode> 一章）。
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
              { name: "type", type: "string", description: "取值 DIRECTORY / FILE / SYMLINK_DIRECTORY / SYMLINK_FILE / OTHER。软链接会按其指向的真实类型展开为 SYMLINK_DIRECTORY 或 SYMLINK_FILE；失效软链接为 OTHER。" },
              { name: "size", type: "long", description: "字节数，目录为 null。" },
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

      <DocSection title="文件读取与保存">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files/content?path=/path/to/file`}
          summary="读取文本文件内容（UTF-8）。文件大小受 oops.pod-filesystem.max-edit-size 限制。"
        />
        <DocSubSection title="响应 (FileContentResponse)">
          <FieldTable
            rows={[
              { name: "path", type: "string" },
              { name: "content", type: "string", description: "文件内容（UTF-8 字符串）。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/instances/{id}/files/content`}
          summary="覆盖写入文本文件内容。父目录需已存在。"
        />
        <DocSubSection title="请求体 (FileSaveRequest)">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, description: "目标文件绝对路径。" },
              { name: "content", type: "string", required: true, description: "新的文件内容。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="文件上传">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/upload?path=/target/dir`}
          summary="以 multipart/form-data 上传文件到沙箱内指定目录。表单字段 file 为必填。"
        />
        <DocSubSection title="查询参数">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, description: "目标父目录绝对路径，会自动追加文件名。" },
            ]}
          />
        </DocSubSection>
        <DocParagraph>
          单次上传大小受 <InlineCode>oops.pod-filesystem.max-upload-size</InlineCode> 限制（默认 50MB），同时受 Spring 的 <InlineCode>spring.servlet.multipart.max-file-size</InlineCode> 约束。文件名会做基本校验，禁止 <InlineCode>.</InlineCode>、<InlineCode>..</InlineCode> 与换行字符。
        </DocParagraph>
      </DocSection>

      <DocSection title="删除、重命名与新建目录">
        <Endpoint
          method="DELETE"
          path={`${PATH_PREFIX}/instances/{id}/files?path=/path/to/target`}
          summary="删除沙箱内的文件或目录（递归）。响应 data 为 null。"
        />
        <DocParagraph>
          OpenAPI 默认禁止 <InlineCode>DELETE</InlineCode>，但 <InlineCode>/openapi/sandbox/**</InlineCode> 例外（详见 <InlineCode>认证</InlineCode>）。
        </DocParagraph>

        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/rename`}
          summary="将沙箱内的文件或目录从 fromPath 移动到 toPath。两端必须在同一文件系统内。"
        />
        <DocSubSection title="请求体 (FileRenameRequest)">
          <FieldTable
            rows={[
              { name: "fromPath", type: "string", required: true, description: "源路径，必须存在。" },
              { name: "toPath", type: "string", required: true, description: "目标路径，必须不存在。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/directory`}
          summary="在沙箱内创建目录（等价于 mkdir -p）。路径已存在（包括同名软链接）会失败。"
        />
        <DocSubSection title="请求体 (DirectoryCreateRequest)">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, description: "要创建的目录绝对路径，不能为根 /。" },
            ]}
          />
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
