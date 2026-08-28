import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications/{name}/deployments"

export default function DeploymentsDocPage() {
  return (
    <DocLayout titleKey="doc.deployments.title">
      <DocSection titleKey="doc.deployments.overview.title">
        <DocParagraph textKey="doc.deployments.overview.p1" />
        <DocParagraph textKey="doc.deployments.overview.p2" />
      </DocSection>

      <DocSection titleKey="doc.deployments.upload.title">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/source-upload`}
          summaryKey="doc.deployments.upload.summary"
        />
        <DocSubSection titleKey="doc.deployments.upload.body.title">
          <FieldTable
            rows={[
              { name: "fileName", type: "string", required: true, descriptionKey: "doc.deployments.upload.body.fileName" },
              { name: "fileSize", type: "long", required: true, descriptionKey: "doc.deployments.upload.body.fileSize" },
              { name: "contentType", type: "string", descriptionKey: "doc.deployments.upload.body.contentType" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.deployments.upload.response.title">
          <FieldTable
            rows={[
              { name: "objectKey", type: "string", descriptionKey: "doc.deployments.upload.response.objectKey" },
              { name: "objectUrl", type: "string", descriptionKey: "doc.deployments.upload.response.objectUrl" },
              { name: "uploadUrl", type: "string", descriptionKey: "doc.deployments.upload.response.uploadUrl" },
              { name: "headers", type: "object", descriptionKey: "doc.deployments.upload.response.headers" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.deployments.upload.example.title">
          <CodeBlock language="bash">{`curl -X PUT "$UPLOAD_URL" \\
  -H "Content-Type: application/zip" \\
  --data-binary @./build.zip`}</CodeBlock>
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.deployments.trigger.title">
        <Endpoint method="POST" path={PATH_PREFIX} summaryKey="doc.deployments.trigger.summary" />
        <DocSubSection titleKey="doc.deployments.trigger.body.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true, descriptionKey: "doc.deployments.trigger.body.environment" },
              { name: "deployMode", type: "string", required: true, descriptionKey: "doc.deployments.trigger.body.deployMode" },
              { name: "strategy", type: "object", required: true, descriptionKey: "doc.deployments.trigger.body.strategy" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.deployments.trigger.git.title">
          <FieldTable
            rows={[
              { name: "strategy.type", type: "string", required: true, descriptionKey: "doc.deployments.trigger.git.type" },
              { name: "strategy.branch", type: "string", required: true, descriptionKey: "doc.deployments.trigger.git.branch" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "prod",
  "deployMode": "IMMEDIATE",
  "strategy": { "type": "GIT", "branch": "main" }
}`}</CodeBlock>
        </DocSubSection>
        <DocSubSection titleKey="doc.deployments.trigger.zip.title">
          <FieldTable
            rows={[
              { name: "strategy.type", type: "string", required: true, descriptionKey: "doc.deployments.trigger.zip.type" },
              { name: "strategy.objectKey", type: "string", descriptionKey: "doc.deployments.trigger.zip.objectKey" },
              { name: "strategy.url", type: "string", descriptionKey: "doc.deployments.trigger.zip.url" },
              { name: "strategy.repository", type: "string", descriptionKey: "doc.deployments.trigger.zip.repository" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "prod",
  "deployMode": "MANUAL",
  "strategy": { "type": "ZIP", "objectKey": "uploads/build-2025-12-01.zip" }
}`}</CodeBlock>
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
