import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications"

export default function ApplicationsDocPage() {
  return (
    <DocLayout title="应用">
      <DocSection title="说明">
        <DocParagraph>
          应用是 OOPS 中的核心资源。每个应用属于一个命名空间，可绑定多个环境，并在每个环境下拥有独立的构建配置、运行时配置与 Service/Ingress 配置。
          以下接口都在路径前缀 <InlineCode>{PATH_PREFIX}</InlineCode> 下，且应用通过 <InlineCode>name</InlineCode>（而非数字 id）寻址。
        </DocParagraph>
      </DocSection>

      <DocSection title="基础 CRUD">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}?keyword=&page=1&size=10&ownerOnly=false`}
          summary="按关键字分页查询命名空间下的应用。"
        />
        <DocSubSection title="查询参数">
          <FieldTable
            rows={[
              { name: "keyword", type: "string", description: "应用名模糊匹配，可选。" },
              { name: "page", type: "int", description: "页码，1 起，默认 1。" },
              { name: "size", type: "int", description: "页大小，默认 10。" },
              { name: "ownerOnly", type: "boolean", description: "只返回当前调用者作为 owner 的应用，默认 false。" },
            ]}
          />
        </DocSubSection>

        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}`} summary="按名称获取单个应用详情。" />

        <Endpoint method="POST" path={PATH_PREFIX} summary="创建应用。响应 data 为新应用的 id。" />
        <DocSubSection title="请求体 (ApplicationConfigDto.Profile)">
          <FieldTable
            rows={[
              { name: "name", type: "string", required: true, description: "应用名，命名空间内唯一，必须符合 K8s 资源名规则。" },
              { name: "description", type: "string" },
              { name: "namespace", type: "string", description: "通常与 URL 中的 namespace 一致；后端以 URL 为准。" },
              { name: "owner", type: "string", description: "创建时会自动覆盖为调用者 userId，可省略。" },
              { name: "collaborators", type: "array", description: "协作者 userId 字符串列表，会去重并排除 owner。" },
            ]}
          />
          <CodeBlock language="json">{`{
  "name": "hello-world",
  "description": "Demo app",
  "collaborators": []
}`}</CodeBlock>
        </DocSubSection>

        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}`} summary="更新应用 Profile（描述、协作者等）。" />
        <DocParagraph>
          请求体结构与创建相同。删除应用接口存在但 OpenAPI 不开放，需要在 UI 中通过 Danger Zone 执行。
        </DocParagraph>
      </DocSection>

      <DocSection title="构建配置">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/build/config`} summary="获取应用的全局构建配置。" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/build/config`} summary="更新构建配置。" />
        <DocSubSection title="请求体 (ApplicationConfigDto.BuildConfig)">
          <FieldTable
            rows={[
              { name: "sourceType", type: "string", required: true, description: "源码类型，取值 GIT 或 ZIP。" },
              { name: "repository", type: "string", description: "Git 仓库地址；ZIP 时为对象存储 key 模板。" },
              { name: "dockerFileConfig.type", type: "string", description: "Dockerfile 类型（内置类型枚举）。" },
              { name: "dockerFileConfig.path", type: "string", description: "Dockerfile 在仓库中的相对路径。" },
              { name: "dockerFileConfig.content", type: "string", description: "内联 Dockerfile 内容（与 path 二选一）。" },
              { name: "buildImage", type: "string", description: "用于构建步骤的镜像。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/environments/build/configs`}
          summary="获取应用在每个环境下的差异化构建配置。"
        />
        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/environments/build/configs`}
          summary="覆盖式更新每环境构建配置（请求体为数组）。"
        />
        <DocSubSection title="请求体元素 (BuildEnvironmentConfig)">
          <FieldTable
            rows={[
              { name: "environmentName", type: "string", required: true },
              { name: "buildCommand", type: "string", description: "该环境下的自定义构建命令。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="运行时配置">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/runtime-spec`} summary="获取运行时配置（含健康检查）。" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/runtime-spec`} summary="更新运行时配置。" />
        <DocSubSection title="请求体 (ApplicationConfigDto.RuntimeSpec)">
          <FieldTable
            rows={[
              { name: "environmentConfigs", type: "array", description: "每个环境的资源配置数组，元素结构见下方 RuntimeEnvironmentConfig。" },
              { name: "healthCheck.enabled", type: "boolean" },
              { name: "healthCheck.path", type: "string", description: "HTTP 健康检查路径，例如 /healthz。" },
              { name: "healthCheck.initialDelaySeconds", type: "int" },
              { name: "healthCheck.periodSeconds", type: "int" },
              { name: "healthCheck.timeoutSeconds", type: "int" },
              { name: "healthCheck.failureThreshold", type: "int" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="RuntimeEnvironmentConfig">
          <FieldTable
            rows={[
              { name: "environmentName", type: "string", required: true },
              { name: "cpuRequest", type: "string", description: "K8s 资源 quantity，例如 100m。" },
              { name: "cpuLimit", type: "string", description: "例如 500m。" },
              { name: "memoryRequest", type: "string", description: "例如 128Mi。" },
              { name: "memoryLimit", type: "string", description: "例如 512Mi。" },
              { name: "replicas", type: "int", description: "副本数，默认为 1。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/environments/runtime-specs`}
          summary="单独获取每环境运行时配置列表（不含 healthCheck）。"
        />
        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/environments/runtime-specs`}
          summary="覆盖式更新每环境运行时配置（请求体为 RuntimeEnvironmentConfig 数组）。"
        />
      </DocSection>

      <DocSection title="环境绑定">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/environments`} summary="返回应用已绑定的环境列表。" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/environments`} summary="覆盖式更新应用的环境绑定。" />
        <DocSubSection title="请求体元素 (EnvironmentBinding)">
          <FieldTable
            rows={[
              { name: "environmentName", type: "string", required: true, description: "目标环境名，需在 /openapi/environments 中存在。" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Service 与域名">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{name}/service`} summary="获取 Service/Ingress 配置。" />
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{name}/service`} summary="更新 Service/Ingress 配置。" />
        <DocSubSection title="请求体 (ApplicationConfigDto.ServiceConfig)">
          <FieldTable
            rows={[
              { name: "port", type: "int", required: true, description: "容器内监听端口。" },
              { name: "environmentConfigs[].environmentName", type: "string", required: true },
              { name: "environmentConfigs[].host", type: "string", description: "外部访问域名，须在域名管理中存在。" },
              { name: "environmentConfigs[].https", type: "boolean", description: "是否启用 HTTPS。" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/service/host-check?host=foo.example.com`}
          summary="检查指定 host 是否已被其他应用占用。"
        />
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/service/cluster-domain?env={env}`}
          summary="返回应用在指定环境下的集群内访问域名。"
        />
      </DocSection>

      <DocSection title="状态与运维">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/status?env={env}`}
          summary="返回应用在指定环境下所有 Pod 的运行状态。"
        />
        <DocSubSection title="响应数组元素 (ApplicationPodStatusView)">
          <FieldTable
            rows={[
              { name: "name", type: "string", description: "Pod 名。" },
              { name: "namespace", type: "string" },
              { name: "status", type: "string", description: "Running / Pending / Failed 等。" },
              { name: "podIP", type: "string" },
              { name: "nodeName", type: "string" },
              { name: "containers[].name", type: "string" },
              { name: "containers[].image", type: "string" },
              { name: "containers[].ready", type: "boolean" },
              { name: "containers[].restartCount", type: "int" },
              { name: "containers[].startedAt", type: "string" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/status/watch?env={env}`}
          summary="通过 Server-Sent Events 推送 Pod 状态变化（响应非 Result 包裹）。"
        />

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/{name}/last-successful-pipeline`}
          summary="返回应用最近一次成功的流水线信息。"
        />

        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/{name}/pods/{pod}/restart?env={env}`}
          summary="重启指定 Pod。请求体为空。"
        />
      </DocSection>
    </DocLayout>
  )
}
