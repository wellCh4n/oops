import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

export default function ConfigMapsDocPage() {
  return (
    <DocLayout title="ConfigMap">
      <DocSection title="说明">
        <DocParagraph>
          每个应用在每个环境下都有一个与应用同名的 ConfigMap 和一个同名的 Secret。以下接口用于统一读取与覆盖这两个资源的键值对：
          通过每个条目的 <InlineCode>secret</InlineCode> 字段区分写入 ConfigMap 还是 Secret。
        </DocParagraph>
        <DocParagraph>
          每个条目可以是环境变量或文件二选一：<strong>未填 <InlineCode>mountPath</InlineCode></strong> 时，该 key 进入
          与应用同名的 ConfigMap / Secret，平台通过 <InlineCode>envFrom</InlineCode> 整体注入为环境变量（非法环境变量名的 key 会被
          Kubernetes 静默跳过）；<strong>填写 <InlineCode>mountPath</InlineCode>（绝对路径）</strong> 时，该 key 改为存入配套的
          <InlineCode>{"{应用名}.files"}</InlineCode> ConfigMap / Secret，并以 <InlineCode>subPath</InlineCode> 挂载为文件，
          <strong>不再注入环境变量</strong>。修改挂载映射（新增/删除挂载）后需重新部署才能让卷生效；仅修改值时滚动重启即可重新读取。
        </DocParagraph>
        <DocParagraph>
          所有接口都需要 <InlineCode>environment</InlineCode> 查询参数指定目标环境名，路径中的占位符使用 <InlineCode>applicationName</InlineCode>（而非其他控制器使用的 <InlineCode>name</InlineCode>）。
        </DocParagraph>
      </DocSection>

      <DocSection title="读取 ConfigMap">
        <Endpoint
          method="GET"
          path="/openapi/namespaces/{namespace}/applications/{applicationName}/configmaps?environment={env}"
          summary="返回应用在指定环境下的全部 ConfigMap 键值对。"
        />
        <DocSubSection title="响应">
          <FieldTable
            rows={[
              { name: "data", type: "array", description: "键值对数组，包含 ConfigMap 与 Secret 中的全部条目，元素结构见下方。" },
              { name: "data[].key", type: "string", description: "键名，即注入到容器的环境变量名。" },
              { name: "data[].value", type: "string", description: "对应的值（Secret 条目已解码为明文）。" },
              { name: "data[].secret", type: "boolean", description: "true 表示该条目来自应用 Secret，false 表示来自 ConfigMap。" },
              { name: "data[].mountPath", type: "string", description: "可选，若该条目被挂载为文件则为容器内的绝对路径。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="覆盖 ConfigMap">
        <Endpoint
          method="PUT"
          path="/openapi/namespaces/{namespace}/applications/{applicationName}/configmaps?environment={env}"
          summary="使用请求体整体覆盖应用在指定环境下的 ConfigMap。"
        />
        <DocSubSection title="请求体">
          <DocParagraph>请求体为数组，每个元素描述一个键值对：</DocParagraph>
          <FieldTable
            rows={[
              { name: "[].key", type: "string", required: true },
              { name: "[].value", type: "string", required: true },
              { name: "[].secret", type: "boolean", description: "true 写入应用 Secret，false（默认）写入 ConfigMap。" },
              { name: "[].mountPath", type: "string", description: "可选，绝对路径；填写后该条目额外挂载为文件。" },
            ]}
          />
          <CodeBlock language="json">{`[
  { "key": "DATABASE_URL", "value": "mysql://..." },
  { "key": "LOG_LEVEL", "value": "info" },
  { "key": "DB_PASSWORD", "value": "s3cr3t", "secret": true },
  { "key": "application.yml", "value": "server:\\n  port: 8080", "mountPath": "/etc/app/application.yml" }
]`}</CodeBlock>
        </DocSubSection>
        <DocSubSection title="说明">
          <DocParagraph>
            这是一个全量覆盖接口，会同时重写应用的 ConfigMap 和 Secret，请求体未包含的键将从对应资源中删除。如需增量更新，请先 GET 当前值，合并后再 PUT。
          </DocParagraph>
          <DocParagraph>
            更新后需要重启应用 Pod（或触发新的部署）才能让容器内的环境变量生效。
          </DocParagraph>
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
