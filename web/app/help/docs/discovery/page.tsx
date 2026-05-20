import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

export default function DiscoveryDocPage() {
  return (
    <DocLayout title="资源发现">
      <DocSection title="说明">
        <DocParagraph>
          自动化脚本通常需要先列出可用的命名空间、环境和域名，再选择目标资源进行操作。资源发现接口位于 <InlineCode>/openapi</InlineCode> 根目录下，不带任何路径参数。
        </DocParagraph>
        <DocParagraph>
          列出环境时会自动脱敏：Kubernetes API token、镜像仓库密码、Git 凭据等敏感字段都会被置为 <InlineCode>null</InlineCode>。
        </DocParagraph>
      </DocSection>

      <DocSection title="列出命名空间">
        <Endpoint method="GET" path="/openapi/namespaces" summary="返回当前用户可见的全部命名空间。" />
        <DocSubSection title="响应">
          <FieldTable
            rows={[
              { name: "id", type: "string", description: "命名空间 NanoId。" },
              { name: "name", type: "string", description: "命名空间名称，应用 URL 中的 {namespace} 即此字段。" },
              { name: "description", type: "string", description: "可选描述。" },
              { name: "createdTime", type: "string (ISO datetime)", description: "创建时间。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="列出环境">
        <Endpoint method="GET" path="/openapi/environments" summary="返回全部环境，敏感字段已脱敏。" />
        <DocSubSection title="响应">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "name", type: "string", description: "环境名称，部署接口中的 environment 即此字段。" },
              { name: "kubernetesApiServer.url", type: "string", description: "Kubernetes API Server 地址。" },
              { name: "kubernetesApiServer.token", type: "string", description: "已脱敏，固定为 null。" },
              { name: "workNamespace", type: "string", description: "构建任务运行的命名空间。" },
              { name: "buildStorageClass", type: "string", description: "构建任务使用的 StorageClass 名称。" },
              { name: "imageRepository.url", type: "string", description: "镜像仓库地址。" },
              { name: "imageRepository.username", type: "string" },
              { name: "imageRepository.password", type: "string", description: "已脱敏，固定为 null。" },
              { name: "gitCredential", type: "object", description: "OpenAPI 出于安全考虑固定返回 null。" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="示例">
          <CodeBlock language="bash">{`curl -H "Authorization: Bearer $OOPS_TOKEN" \\
  https://oops.example.com/openapi/environments`}</CodeBlock>
        </DocSubSection>
      </DocSection>

      <DocSection title="列出域名">
        <Endpoint method="GET" path="/openapi/domains" summary="返回平台管理的全部域名（含 HTTPS 与证书状态）。" />
        <DocSubSection title="响应">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "host", type: "string", description: "域名，支持通配符（前缀 *.）。" },
              { name: "description", type: "string" },
              { name: "https", type: "boolean", description: "是否启用 HTTPS。" },
              { name: "certMode", type: "string", description: "AUTO（自动签发）或 UPLOADED（用户上传证书）。" },
              { name: "hasUploadedCert", type: "boolean", description: "是否已上传 PEM 证书。" },
              { name: "certSubject", type: "string", description: "已上传证书的 Subject，未上传时为 null。" },
              { name: "certNotAfter", type: "string", description: "证书的到期时间（ISO datetime），未上传时为 null。" },
              { name: "createdTime", type: "string" },
            ]}
          />
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
