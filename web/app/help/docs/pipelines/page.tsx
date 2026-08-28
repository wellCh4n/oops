import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications/{name}/pipelines"

export default function PipelinesDocPage() {
  return (
    <DocLayout titleKey="doc.pipelines.title">
      <DocSection titleKey="doc.pipelines.overview.title">
        <DocParagraph textKey="doc.pipelines.overview.p1" />
        <DocParagraph textKey="doc.pipelines.overview.p2" />
      </DocSection>

      <DocSection titleKey="doc.pipelines.list.title">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}?environment=&page=1&size=10`}
          summaryKey="doc.pipelines.list.summary"
        />
        <DocSubSection titleKey="doc.pipelines.list.params.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", descriptionKey: "doc.pipelines.list.params.environment" },
              { name: "page", type: "int", descriptionKey: "doc.pipelines.list.params.page" },
              { name: "size", type: "int", descriptionKey: "doc.pipelines.list.params.size" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.pipelines.list.item.title">
          <FieldTable
            rows={[
              { name: "id", type: "string", descriptionKey: "doc.pipelines.list.item.id" },
              { name: "name", type: "string", descriptionKey: "doc.pipelines.list.item.name" },
              { name: "status", type: "string", descriptionKey: "doc.pipelines.list.item.status" },
              { name: "artifact", type: "string", descriptionKey: "doc.pipelines.list.item.artifact" },
              { name: "environment", type: "string" },
              { name: "publishType", type: "string", descriptionKey: "doc.pipelines.list.item.publishType" },
              { name: "publishConfig", type: "object", descriptionKey: "doc.pipelines.list.item.publishConfig" },
              { name: "deployMode", type: "string", descriptionKey: "doc.pipelines.list.item.deployMode" },
              { name: "operatorId", type: "string", descriptionKey: "doc.pipelines.list.item.operatorId" },
              { name: "operatorName", type: "string" },
              { name: "message", type: "string", descriptionKey: "doc.pipelines.list.item.message" },
              { name: "triggerType", type: "string", descriptionKey: "doc.pipelines.list.item.triggerType" },
              { name: "rollbackFromPipelineId", type: "string", descriptionKey: "doc.pipelines.list.item.rollbackFrom" },
              { name: "createdTime", type: "string" },
            ]}
          />
          <DocParagraph textKey="doc.pipelines.list.item.statuses" />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.pipelines.get.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{id}`} summaryKey="doc.pipelines.get.summary" />
      </DocSection>

      <DocSection titleKey="doc.pipelines.stop.title">
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{id}/stop`} summaryKey="doc.pipelines.stop.summary" />
        <DocParagraph textKey="doc.pipelines.stop.p1" />
      </DocSection>

      <DocSection titleKey="doc.pipelines.deploy.title">
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{id}/deploy`} summaryKey="doc.pipelines.deploy.summary" />
        <DocParagraph textKey="doc.pipelines.deploy.p1" />
      </DocSection>

      <DocSection titleKey="doc.pipelines.rollback.title">
        <Endpoint method="POST" path={`${PATH_PREFIX}/{id}/rollback`} summaryKey="doc.pipelines.rollback.summary" />
        <DocParagraph textKey="doc.pipelines.rollback.p1" />
      </DocSection>
    </DocLayout>
  )
}
