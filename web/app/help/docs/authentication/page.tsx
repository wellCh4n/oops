import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocParagraph, DocSection } from "@/components/doc/doc-layout"

export default function AuthenticationDocPage() {
  return (
    <DocLayout titleKey="doc.authentication.title">
      <DocSection titleKey="doc.authentication.token.title">
        <DocParagraph textKey="doc.authentication.token.p1" />
        <CodeBlock language="http">{`Authorization: Bearer <access_token>`}</CodeBlock>
        <DocParagraph textKey="doc.authentication.token.p2" />
        <DocParagraph textKey="doc.authentication.token.p3" />
      </DocSection>

      <DocSection titleKey="doc.authentication.limits.title">
        <DocParagraph textKey="doc.authentication.limits.p1" />
        <DocParagraph textKey="doc.authentication.limits.p2" />
      </DocSection>

      <DocSection titleKey="doc.authentication.example.title">
        <DocParagraph textKey="doc.authentication.example.p1" />
        <CodeBlock language="bash">{`curl -H "Authorization: Bearer $OOPS_TOKEN" \\
  https://oops.example.com/openapi/namespaces`}</CodeBlock>
        <DocParagraph textKey="doc.authentication.example.p2" />
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
