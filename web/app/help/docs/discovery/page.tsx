import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

export default function DiscoveryDocPage() {
  return (
    <DocLayout titleKey="doc.discovery.title">
      <DocSection titleKey="doc.discovery.overview.title">
        <DocParagraph textKey="doc.discovery.overview.p1" />
        <DocParagraph textKey="doc.discovery.overview.p2" />
      </DocSection>

      <DocSection titleKey="doc.discovery.namespaces.title">
        <Endpoint method="GET" path="/openapi/namespaces" summaryKey="doc.discovery.namespaces.summary" />
        <DocSubSection titleKey="doc.discovery.namespaces.response.title">
          <FieldTable
            rows={[
              { name: "id", type: "string", descriptionKey: "doc.discovery.namespaces.response.id" },
              { name: "name", type: "string", descriptionKey: "doc.discovery.namespaces.response.name" },
              { name: "description", type: "string", descriptionKey: "doc.discovery.namespaces.response.description" },
              { name: "createdTime", type: "string (ISO datetime)", descriptionKey: "doc.discovery.namespaces.response.createdTime" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.discovery.environments.title">
        <Endpoint method="GET" path="/openapi/environments" summaryKey="doc.discovery.environments.summary" />
        <DocSubSection titleKey="doc.discovery.environments.response.title">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "name", type: "string", descriptionKey: "doc.discovery.environments.response.name" },
              { name: "kubernetesApiServer.url", type: "string", descriptionKey: "doc.discovery.environments.response.apiServerUrl" },
              { name: "kubernetesApiServer.token", type: "string", descriptionKey: "doc.discovery.environments.response.apiServerToken" },
              { name: "workNamespace", type: "string", descriptionKey: "doc.discovery.environments.response.workNamespace" },
              { name: "buildStorageClass", type: "string", descriptionKey: "doc.discovery.environments.response.buildStorageClass" },
              { name: "imageRepository.url", type: "string", descriptionKey: "doc.discovery.environments.response.registryUrl" },
              { name: "imageRepository.username", type: "string" },
              { name: "imageRepository.password", type: "string", descriptionKey: "doc.discovery.environments.response.registryPassword" },
              { name: "gitCredential", type: "object", descriptionKey: "doc.discovery.environments.response.gitCredential" },
            ]}
          />
        </DocSubSection>
        <DocSubSection titleKey="doc.discovery.environments.example.title">
          <CodeBlock language="bash">{`curl -H "Authorization: Bearer $OOPS_TOKEN" \\
  https://oops.example.com/openapi/environments`}</CodeBlock>
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.discovery.domains.title">
        <Endpoint method="GET" path="/openapi/domains" summaryKey="doc.discovery.domains.summary" />
        <DocSubSection titleKey="doc.discovery.domains.response.title">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "host", type: "string", descriptionKey: "doc.discovery.domains.response.host" },
              { name: "description", type: "string" },
              { name: "https", type: "boolean", descriptionKey: "doc.discovery.domains.response.https" },
              { name: "certMode", type: "string", descriptionKey: "doc.discovery.domains.response.certMode" },
              { name: "hasUploadedCert", type: "boolean", descriptionKey: "doc.discovery.domains.response.hasUploadedCert" },
              { name: "certSubject", type: "string", descriptionKey: "doc.discovery.domains.response.certSubject" },
              { name: "certNotAfter", type: "string", descriptionKey: "doc.discovery.domains.response.certNotAfter" },
              { name: "createdTime", type: "string" },
            ]}
          />
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
