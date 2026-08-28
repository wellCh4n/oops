import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH = "/openapi/namespaces/{namespace}/applications/{applicationName}/configmaps?environment={env}"

export default function ConfigMapsDocPage() {
  return (
    <DocLayout titleKey="doc.configmaps.title">
      <DocSection titleKey="doc.configmaps.overview.title">
        <DocParagraph textKey="doc.configmaps.overview.p1" />
        <DocParagraph textKey="doc.configmaps.overview.p2" />
        <DocParagraph textKey="doc.configmaps.overview.p3" />
      </DocSection>

      <DocSection titleKey="doc.configmaps.read.title">
        <Endpoint method="GET" path={PATH} summaryKey="doc.configmaps.read.summary" />
        <DocSubSection titleKey="doc.configmaps.read.response.title">
          <FieldTable
            rows={[
              { name: "data", type: "array", descriptionKey: "doc.configmaps.read.response.data" },
              { name: "data[].key", type: "string", descriptionKey: "doc.configmaps.read.response.key" },
              { name: "data[].value", type: "string", descriptionKey: "doc.configmaps.read.response.value" },
              { name: "data[].secret", type: "boolean", descriptionKey: "doc.configmaps.read.response.secret" },
              { name: "data[].mountPath", type: "string", descriptionKey: "doc.configmaps.read.response.mountPath" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.configmaps.write.title">
        <Endpoint method="PUT" path={PATH} summaryKey="doc.configmaps.write.summary" />
        <DocSubSection titleKey="doc.configmaps.write.body.title">
          <DocParagraph textKey="doc.configmaps.write.body.p1" />
          <FieldTable
            rows={[
              { name: "[].key", type: "string", required: true },
              { name: "[].value", type: "string", required: true },
              { name: "[].secret", type: "boolean", descriptionKey: "doc.configmaps.write.body.secret" },
              { name: "[].mountPath", type: "string", descriptionKey: "doc.configmaps.write.body.mountPath" },
            ]}
          />
          <CodeBlock language="json">{`[
  { "key": "DATABASE_URL", "value": "mysql://..." },
  { "key": "LOG_LEVEL", "value": "info" },
  { "key": "DB_PASSWORD", "value": "s3cr3t", "secret": true },
  { "key": "application.yml", "value": "server:\\n  port: 8080", "mountPath": "/etc/app/application.yml" }
]`}</CodeBlock>
        </DocSubSection>
        <DocSubSection titleKey="doc.configmaps.write.notes.title">
          <DocParagraph textKey="doc.configmaps.write.notes.p1" />
          <DocParagraph textKey="doc.configmaps.write.notes.p2" />
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
