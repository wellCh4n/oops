import { InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/namespaces/{namespace}/applications/{name}/pipelines"

export default function PipelinesDocPage() {
  return (
    <DocLayout title="流水线">
      <DocSection title="说明">
        <DocParagraph>
          每次触发部署都会在数据库中创建一条 Pipeline 记录，对应 Kubernetes 上的一个 Job：
          先克隆源码，可选执行构建命令，再用 Buildah 打包镜像并推送。
          构建完成后根据 <InlineCode>deployMode</InlineCode> 决定是否立即部署。流水线日志通过 <InlineCode>WebSocket</InlineCode> 推送，OpenAPI 不开放日志接口。
        </DocParagraph>
        <DocParagraph>
          以下接口都在路径前缀 <InlineCode>{PATH_PREFIX}</InlineCode> 下，每条流水线通过 NanoId 形式的 <InlineCode>id</InlineCode> 寻址。
        </DocParagraph>
      </DocSection>

      <DocSection title="列出流水线">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}?environment=&page=1&size=10`}
          summary="分页查询应用的流水线，可按环境过滤。"
        />
        <DocSubSection title="查询参数">
          <FieldTable
            rows={[
              { name: "environment", type: "string", description: "可选，按环境名过滤。" },
              { name: "page", type: "int", description: "1 起，默认 1。" },
              { name: "size", type: "int", description: "默认 10。" },
            ]}
          />
        </DocSubSection>
        <DocSubSection title="响应分页元素 (PipelineDto)">
          <FieldTable
            rows={[
              { name: "id", type: "string", description: "流水线 ID。" },
              { name: "name", type: "string", description: "流水线名称（K8s Job 名）。" },
              { name: "status", type: "string", description: "流水线状态枚举，见下方说明。" },
              { name: "artifact", type: "string", description: "构建产出物（镜像 tag 等）。" },
              { name: "environment", type: "string" },
              { name: "branch", type: "string", description: "GIT 源时记录的分支或引用。" },
              { name: "deployMode", type: "string", description: "取值 IMMEDIATE 或 MANUAL。" },
              { name: "operatorId", type: "string", description: "触发者 userId。" },
              { name: "operatorName", type: "string" },
              { name: "message", type: "string", description: "失败原因或状态备注。" },
              { name: "createdTime", type: "string" },
            ]}
          />
          <DocParagraph>
            <InlineCode>status</InlineCode> 取值：<InlineCode>RUNNING</InlineCode>、<InlineCode>BUILD_SUCCEEDED</InlineCode>、<InlineCode>DEPLOYING</InlineCode>、<InlineCode>ROLLING_OUT</InlineCode>、<InlineCode>SUCCEEDED</InlineCode>、<InlineCode>ERROR</InlineCode>、<InlineCode>STOPPED</InlineCode>。
          </DocParagraph>
        </DocSubSection>
      </DocSection>

      <DocSection title="获取单条流水线">
        <Endpoint method="GET" path={`${PATH_PREFIX}/{id}`} summary="按 id 获取流水线详情。" />
      </DocSection>

      <DocSection title="停止流水线">
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{id}/stop`} summary="停止运行中的流水线。请求体为空。" />
        <DocParagraph>
          只对处于 <InlineCode>RUNNING</InlineCode> / <InlineCode>DEPLOYING</InlineCode> 状态的流水线有效。已完成的流水线调用会得到业务错误响应。
        </DocParagraph>
      </DocSection>

      <DocSection title="手动部署已构建的流水线">
        <Endpoint method="PUT" path={`${PATH_PREFIX}/{id}/deploy`} summary="对 deployMode=MANUAL 且已构建成功的流水线触发部署。请求体为空。" />
        <DocParagraph>
          仅对 <InlineCode>status=BUILD_SUCCEEDED</InlineCode> 的流水线生效。<InlineCode>IMMEDIATE</InlineCode> 模式的流水线会在构建结束后自动进入部署阶段，无需调用此接口。
        </DocParagraph>
      </DocSection>
    </DocLayout>
  )
}
