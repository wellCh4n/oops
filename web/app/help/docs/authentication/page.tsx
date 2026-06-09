import { CodeBlock, InlineCode } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection } from "@/components/doc/doc-layout"

export default function AuthenticationDocPage() {
  return (
    <DocLayout title="认证">
      <DocSection title="访问令牌">
        <DocParagraph>
          调用 <InlineCode>/openapi/**</InlineCode> 接口需要在请求头中携带用户访问令牌（Access Token）：
        </DocParagraph>
        <CodeBlock language="http">{`Authorization: Bearer <access_token>`}</CodeBlock>
        <DocParagraph>
          访问令牌可以在 <InlineCode>设置 → 个人资料</InlineCode> 页面生成或重置。一个用户同时只能持有一个访问令牌，重置后旧令牌立即失效。
        </DocParagraph>
        <DocParagraph>
          令牌没有过期时间，但与用户角色一致：在 OpenAPI 上调用某个接口的权限等价于该用户通过 UI 登录后调用对应 <InlineCode>/api/**</InlineCode> 接口的权限。
        </DocParagraph>
      </DocSection>

      <DocSection title="限制">
        <DocParagraph>
          部分危险操作会被标记为不对 OpenAPI 开放，调用时直接返回 <InlineCode>405 Method Not Allowed</InlineCode>。
          例如应用删除需要在 UI 的 Danger Zone 中执行；沙箱实例和沙箱文件删除属于沙箱生命周期的一部分，允许通过 <InlineCode>/openapi/sandbox/**</InlineCode> 调用。
        </DocParagraph>
        <DocParagraph>
          缺失或非法的 <InlineCode>Authorization</InlineCode> 头会返回 <InlineCode>401 Unauthorized</InlineCode>。
          令牌格式正确但不存在或已被重置时也返回 <InlineCode>401</InlineCode>。
        </DocParagraph>
      </DocSection>

      <DocSection title="示例">
        <DocParagraph>使用 curl 调用资源发现接口：</DocParagraph>
        <CodeBlock language="bash">{`curl -H "Authorization: Bearer $OOPS_TOKEN" \\
  https://oops.example.com/openapi/namespaces`}</CodeBlock>
        <DocParagraph>典型响应：</DocParagraph>
        <CodeBlock language="json">{`{
  "success": true,
  "message": null,
  "data": [
    { "id": "...", "name": "default", "description": "..." }
  ]
}`}</CodeBlock>
      </DocSection>
    </DocLayout>
  )
}
