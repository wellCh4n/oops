import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

export default function ConfigMapsDocPage() {
  return (
    <DocLayout title="ConfigMap">
      <DocSection title="说明">
        <DocParagraph>
          每个应用在每个环境下都有一个与应用同名的 ConfigMap，平台会通过 <InlineCode>envFrom</InlineCode> 将其注入到应用容器的环境变量。
          以下接口用于读取与覆盖该 ConfigMap 的键值对。
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
              { name: "data", type: "array", description: "ConfigMap 键值对数组，元素结构见下方。" },
              { name: "data[].key", type: "string", description: "ConfigMap 中的键，即注入到容器的环境变量名。" },
              { name: "data[].value", type: "string", description: "对应的值。" },
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
            ]}
          />
          <CodeBlock language="json">{`[
  { "key": "DATABASE_URL", "value": "mysql://..." },
  { "key": "LOG_LEVEL", "value": "info" }
]`}</CodeBlock>
        </DocSubSection>
        <DocSubSection title="说明">
          <DocParagraph>
            这是一个全量覆盖接口，请求体未包含的键将从 ConfigMap 中删除。如需增量更新，请先 GET 当前值，合并后再 PUT。
          </DocParagraph>
          <DocParagraph>
            更新后需要重启应用 Pod（或触发新的部署）才能让容器内的环境变量生效。
          </DocParagraph>
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
