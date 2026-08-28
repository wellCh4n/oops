import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications"

export default function ApplicationsDocPage() {
  return (
    <DocLayout titleKey="doc.applications.title">
      <DocSection titleKey="doc.applications.overview.title">
        <DocParagraph textKey="doc.applications.overview.p1" />
      </DocSection>

      <DocSection titleKey="doc.applications.crud.title">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}?keyword=&page=1&size=10&ownerOnly=false`}
          summaryKey="doc.applications.crud.list.summary"
        />
        <DocSubSection titleKey="doc.applications.crud.list.params.title">
          <FieldTable
            rows={[
              { name: "keyword", type: "string", descriptionKey: "doc.applications.crud.list.params.keyword" },
              { name: "page", type: "int", descriptionKey: "doc.applications.crud.list.params.page" },
              { name: "size", type: "int", descriptionKey: "doc.applications.crud.list.params.size" },
              { name: "ownerOnly", type: "boolean", descriptionKey: "doc.applications.crud.list.params.ownerOnly" },
            ]}
          />
        </DocSubSection>

        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}`} summaryKey="doc.applications.crud.get.summary" />

        <Endpoint method="POST" path={PATH_PREFIX} summaryKey="doc.applications.crud.create.summary" />
        <DocSubSection titleKey="doc.applications.crud.create.body.title">
          <FieldTable
            rows={[
              { name: "name", type: "string", required: true, descriptionKey: "doc.applications.crud.create.body.name" },
              { name: "description", type: "string" },
              { name: "namespace", type: "string", descriptionKey: "doc.applications.crud.create.body.namespace" },
              { name: "owner", type: "string", descriptionKey: "doc.applications.crud.create.body.owner" },
              { name: "collaborators", type: "array", descriptionKey: "doc.applications.crud.create.body.collaborators" },
            ]}
          />
          <CodeBlock language="json">{`{
  "name": "hello-world",
  "description": "Demo app",
  "collaborators": []
}`}</CodeBlock>
        </DocSubSection>

        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}`} summaryKey="doc.applications.crud.update.summary" />
        <DocParagraph textKey="doc.applications.crud.update.p1" />
      </DocSection>

      <DocSection titleKey="doc.applications.build.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/build/config`} summaryKey="doc.applications.build.get.summary" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/build/config`} summaryKey="doc.applications.build.put.summary" />
        <DocSubSection titleKey="doc.applications.build.body.title">
          <FieldTable
            rows={[
              { name: "sourceType", type: "string", required: true, descriptionKey: "doc.applications.build.body.sourceType" },
              { name: "repository", type: "string", descriptionKey: "doc.applications.build.body.repository" },
              { name: "dockerFileConfig.type", type: "string", descriptionKey: "doc.applications.build.body.dockerfileType" },
              { name: "dockerFileConfig.path", type: "string", descriptionKey: "doc.applications.build.body.dockerfilePath" },
              { name: "dockerFileConfig.content", type: "string", descriptionKey: "doc.applications.build.body.dockerfileContent" },
              { name: "buildImage", type: "string", descriptionKey: "doc.applications.build.body.buildImage" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/environments/build/configs`}
          summaryKey="doc.applications.build.envGet.summary"
        />
        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/environments/build/configs`}
          summaryKey="doc.applications.build.envPut.summary"
        />
        <DocSubSection titleKey="doc.applications.build.envBody.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true },
              { name: "buildCommand", type: "string", descriptionKey: "doc.applications.build.envBody.buildCommand" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.applications.runtime.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/runtime-spec`} summaryKey="doc.applications.runtime.get.summary" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/runtime-spec`} summaryKey="doc.applications.runtime.put.summary" />
        <DocSubSection titleKey="doc.applications.runtime.body.title">
          <FieldTable
            rows={[
              { name: "environmentConfigs", type: "array", descriptionKey: "doc.applications.runtime.body.environmentConfigs" },
              { name: "healthCheck.liveness.enabled", type: "boolean" },
              { name: "healthCheck.liveness.path", type: "string", descriptionKey: "doc.applications.runtime.body.livenessPath" },
              { name: "healthCheck.liveness.initialDelaySeconds", type: "int" },
              { name: "healthCheck.liveness.periodSeconds", type: "int" },
              { name: "healthCheck.liveness.timeoutSeconds", type: "int" },
              { name: "healthCheck.liveness.failureThreshold", type: "int" },
              { name: "healthCheck.readiness.enabled", type: "boolean" },
              { name: "healthCheck.readiness.path", type: "string", descriptionKey: "doc.applications.runtime.body.readinessPath" },
              { name: "healthCheck.readiness.initialDelaySeconds", type: "int" },
              { name: "healthCheck.readiness.periodSeconds", type: "int" },
              { name: "healthCheck.readiness.timeoutSeconds", type: "int" },
              { name: "healthCheck.readiness.failureThreshold", type: "int" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.applications.runtime.envConfig.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true },
              { name: "cpuRequest", type: "string", descriptionKey: "doc.applications.runtime.envConfig.cpuRequest" },
              { name: "cpuLimit", type: "string", descriptionKey: "doc.applications.runtime.envConfig.cpuLimit" },
              { name: "memoryRequest", type: "string", descriptionKey: "doc.applications.runtime.envConfig.memoryRequest" },
              { name: "memoryLimit", type: "string", descriptionKey: "doc.applications.runtime.envConfig.memoryLimit" },
              { name: "replicas", type: "int", descriptionKey: "doc.applications.runtime.envConfig.replicas" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/environments/runtime-specs`}
          summaryKey="doc.applications.runtime.envGet.summary"
        />
        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/environments/runtime-specs`}
          summaryKey="doc.applications.runtime.envPut.summary"
        />
      </DocSection>

      <DocSection titleKey="doc.applications.environments.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/environments`} summaryKey="doc.applications.environments.get.summary" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/environments`} summaryKey="doc.applications.environments.put.summary" />
        <DocSubSection titleKey="doc.applications.environments.body.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true, descriptionKey: "doc.applications.environments.body.environment" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.applications.expert.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/expert-config`} summaryKey="doc.applications.expert.get.summary" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/expert-config`} summaryKey="doc.applications.expert.put.summary" />
        <DocSubSection titleKey="doc.applications.expert.body.title">
          <FieldTable
            rows={[
              { name: "environmentConfigs", type: "array", descriptionKey: "doc.applications.expert.body.environmentConfigs" },
              { name: "environmentConfigs[].environment", type: "string", required: true },
              { name: "environmentConfigs[].serviceAccountName", type: "string", descriptionKey: "doc.applications.expert.body.serviceAccountName" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.applications.service.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/service`} summaryKey="doc.applications.service.get.summary" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/service`} summaryKey="doc.applications.service.put.summary" />
        <DocSubSection titleKey="doc.applications.service.body.title">
          <FieldTable
            rows={[
              { name: "port", type: "int", required: true, descriptionKey: "doc.applications.service.body.port" },
              { name: "environmentConfigs[].environment", type: "string", required: true },
              { name: "environmentConfigs[].host", type: "string", descriptionKey: "doc.applications.service.body.host" },
              { name: "environmentConfigs[].https", type: "boolean", descriptionKey: "doc.applications.service.body.https" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/service/host-check?host=foo.example.com`}
          summaryKey="doc.applications.service.hostCheck.summary"
        />
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/service/cluster-domain?environment={env}`}
          summaryKey="doc.applications.service.clusterDomain.summary"
        />
      </DocSection>

      <DocSection titleKey="doc.applications.status.title">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/status?environment={env}`}
          summaryKey="doc.applications.status.get.summary"
        />
        <DocSubSection titleKey="doc.applications.status.podView.title">
          <FieldTable
            rows={[
              { name: "name", type: "string", descriptionKey: "doc.applications.status.podView.name" },
              { name: "namespace", type: "string" },
              { name: "status", type: "string", descriptionKey: "doc.applications.status.podView.status" },
              { name: "podIP", type: "string" },
              { name: "nodeName", type: "string" },
              { name: "containers[].name", type: "string" },
              { name: "containers[].image", type: "string" },
              { name: "containers[].ready", type: "boolean" },
              { name: "containers[].restartCount", type: "int" },
              { name: "containers[].startedAt", type: "string" },
              { name: "containers[].reason", type: "string" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/status/watch?environment={env}`}
          summaryKey="doc.applications.status.watch.summary"
        />

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/current-image?environment={env}`}
          summaryKey="doc.applications.status.currentImage.summary"
        />

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/last-successful-pipeline`}
          summaryKey="doc.applications.status.lastPipeline.summary"
        />

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/resources?environment={env}`}
          summaryKey="doc.applications.status.resources.summary"
        />
        <DocSubSection titleKey="doc.applications.status.resourceView.title">
          <FieldTable
            rows={[
              { name: "kind", type: "string", descriptionKey: "doc.applications.status.resourceView.kind" },
              { name: "name", type: "string", descriptionKey: "doc.applications.status.resourceView.name" },
              { name: "data", type: "string", descriptionKey: "doc.applications.status.resourceView.data" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/pods/{pod}/restart?environment={env}`}
          summaryKey="doc.applications.status.restart.summary"
        />
      </DocSection>
    </DocLayout>
  )
}
