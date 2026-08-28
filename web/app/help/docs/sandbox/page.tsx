import { CodeBlock } from "@/components/doc/code-block"
import { DocLayout, DocList, DocParagraph, DocSection, DocSubSection } from "@/components/doc/doc-layout"
import { Endpoint } from "@/components/doc/endpoint"
import { FieldTable } from "@/components/doc/field-table"

const PATH_PREFIX = "/openapi/sandbox"

export default function SandboxDocPage() {
  return (
    <DocLayout titleKey="doc.sandbox.title">
      <DocSection titleKey="doc.sandbox.overview.title">
        <DocParagraph textKey="doc.sandbox.overview.p1" />
        <DocList itemKeys={["doc.sandbox.overview.li1", "doc.sandbox.overview.li2"]} />
        <DocParagraph textKey="doc.sandbox.overview.p2" />
      </DocSection>

      <DocSection titleKey="doc.sandbox.images.title">
        <Endpoint method="GET" path={`${PATH_PREFIX}/images`} summaryKey="doc.sandbox.images.summary" />
      </DocSection>

      <DocSection titleKey="doc.sandbox.executions.title">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/executions`}
          summaryKey="doc.sandbox.executions.summary"
        />
        <DocSubSection titleKey="doc.sandbox.executions.body.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true, descriptionKey: "doc.sandbox.executions.body.environment" },
              { name: "image", type: "string", required: true, descriptionKey: "doc.sandbox.executions.body.image" },
              { name: "commands", type: "array", required: true, descriptionKey: "doc.sandbox.executions.body.commands" },
              { name: "timeoutSeconds", type: "int", descriptionKey: "doc.sandbox.executions.body.timeoutSeconds" },
              { name: "ttlSecondsAfterFinished", type: "int", descriptionKey: "doc.sandbox.executions.body.ttl" },
              { name: "cpu", type: "object", descriptionKey: "doc.sandbox.executions.body.cpu" },
              { name: "memory", type: "object", descriptionKey: "doc.sandbox.executions.body.memory" },
              { name: "env", type: "object", descriptionKey: "doc.sandbox.executions.body.env" },
              { name: "stream", type: "boolean", descriptionKey: "doc.sandbox.executions.body.stream" },
            ]}
          />
          <CodeBlock language="json">{`{
  "environment": "dev",
  "image": "python:3.12-slim",
  "commands": ["python -c 'print(\\"hello\\")'"],
  "timeoutSeconds": 60,
  "env": { "FOO": "bar" }
}`}</CodeBlock>
        </DocSubSection>
        <DocSubSection titleKey="doc.sandbox.executions.result.title">
          <FieldTable
            rows={[
              { name: "exitCode", type: "int", descriptionKey: "doc.sandbox.executions.result.exitCode" },
              { name: "stdout", type: "string" },
              { name: "stderr", type: "string" },
              { name: "logs", type: "string", descriptionKey: "doc.sandbox.executions.result.logs" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.sandbox.instances.title">
        <Endpoint method="POST" path={`${PATH_PREFIX}/instances`} summaryKey="doc.sandbox.instances.create.summary" />
        <DocSubSection titleKey="doc.sandbox.instances.create.body.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", required: true },
              { name: "name", type: "string", descriptionKey: "doc.sandbox.instances.create.body.name" },
              { name: "image", type: "string", required: true, descriptionKey: "doc.sandbox.instances.create.body.image" },
              { name: "cpu", type: "object", descriptionKey: "doc.sandbox.instances.create.body.cpu" },
              { name: "memory", type: "object", descriptionKey: "doc.sandbox.instances.create.body.memory" },
              { name: "env", type: "object", descriptionKey: "doc.sandbox.instances.create.body.env" },
              { name: "useDefaultKeepalive", type: "boolean", descriptionKey: "doc.sandbox.instances.create.body.keepalive" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances?environment=&image=`}
          summaryKey="doc.sandbox.instances.list.summary"
        />
        <DocSubSection titleKey="doc.sandbox.instances.list.params.title">
          <FieldTable
            rows={[
              { name: "environment", type: "string", descriptionKey: "doc.sandbox.instances.list.params.environment" },
              { name: "image", type: "string", descriptionKey: "doc.sandbox.instances.list.params.image" },
            ]}
          />
        </DocSubSection>

        <Endpoint method="GET" path={`${PATH_PREFIX}/instances/{id}`} summaryKey="doc.sandbox.instances.get.summary" />
        <DocSubSection titleKey="doc.sandbox.instances.get.response.title">
          <FieldTable
            rows={[
              { name: "id", type: "string" },
              { name: "name", type: "string" },
              { name: "environment", type: "string" },
              { name: "image", type: "string" },
              { name: "status", type: "string", descriptionKey: "doc.sandbox.instances.get.response.status" },
              { name: "createdBy", type: "string" },
              { name: "createdByName", type: "string" },
              { name: "createdAt", type: "string (ISO instant)" },
              { name: "cpuRequest / cpuLimit", type: "string" },
              { name: "memoryRequest / memoryLimit", type: "string" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="DELETE"
          path={`${PATH_PREFIX}/instances/{id}`}
          summaryKey="doc.sandbox.instances.delete.summary"
        />
        <DocParagraph textKey="doc.sandbox.instances.delete.p1" />
      </DocSection>

      <DocSection titleKey="doc.sandbox.exec.title">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/exec`}
          summaryKey="doc.sandbox.exec.summary"
        />
        <DocSubSection titleKey="doc.sandbox.exec.body.title">
          <FieldTable
            rows={[
              { name: "command", type: "string", required: true, descriptionKey: "doc.sandbox.exec.body.command" },
              { name: "timeoutSeconds", type: "int", descriptionKey: "doc.sandbox.exec.body.timeoutSeconds" },
              { name: "stream", type: "boolean", descriptionKey: "doc.sandbox.exec.body.stream" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.sandbox.terminal.title">
        <Endpoint
          method="WS"
          path={`${PATH_PREFIX}/instances/{id}/terminal`}
          summaryKey="doc.sandbox.terminal.summary"
        />
        <DocParagraph textKey="doc.sandbox.terminal.p1" />
        <CodeBlock language="python">{`import asyncio, websockets

async def main():
    url = "ws://oops.example.com/openapi/sandbox/instances/{id}/terminal"
    headers = {"Authorization": "Bearer <access token>"}
    async with websockets.connect(url, additional_headers=headers) as ws:
        await ws.send("ls -la\\n")
        print(await ws.recv())

asyncio.run(main())`}</CodeBlock>
      </DocSection>

      <DocSection titleKey="doc.sandbox.files.title">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files?path=/`}
          summaryKey="doc.sandbox.files.list.summary"
        />
        <DocSubSection titleKey="doc.sandbox.files.list.entry.title">
          <FieldTable
            rows={[
              { name: "name", type: "string" },
              { name: "path", type: "string", descriptionKey: "doc.sandbox.files.list.entry.path" },
              { name: "type", type: "string", descriptionKey: "doc.sandbox.files.list.entry.type" },
              { name: "size", type: "long", descriptionKey: "doc.sandbox.files.list.entry.size" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files/download?path=/path/to/file`}
          summaryKey="doc.sandbox.files.download.summary"
        />
        <DocParagraph textKey="doc.sandbox.files.download.p1" />
      </DocSection>

      <DocSection titleKey="doc.sandbox.content.title">
        <Endpoint
          method="GET"
          path={`${PATH_PREFIX}/instances/{id}/files/content?path=/path/to/file`}
          summaryKey="doc.sandbox.content.get.summary"
        />
        <DocSubSection titleKey="doc.sandbox.content.get.response.title">
          <FieldTable
            rows={[
              { name: "path", type: "string" },
              { name: "content", type: "string", descriptionKey: "doc.sandbox.content.get.response.content" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="PUT"
          path={`${PATH_PREFIX}/instances/{id}/files/content`}
          summaryKey="doc.sandbox.content.put.summary"
        />
        <DocSubSection titleKey="doc.sandbox.content.put.body.title">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, descriptionKey: "doc.sandbox.content.put.body.path" },
              { name: "content", type: "string", required: true, descriptionKey: "doc.sandbox.content.put.body.content" },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection titleKey="doc.sandbox.upload.title">
        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/upload?path=/target/dir`}
          summaryKey="doc.sandbox.upload.summary"
        />
        <DocSubSection titleKey="doc.sandbox.upload.params.title">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, descriptionKey: "doc.sandbox.upload.params.path" },
            ]}
          />
        </DocSubSection>
        <DocParagraph textKey="doc.sandbox.upload.p1" />
      </DocSection>

      <DocSection titleKey="doc.sandbox.mutate.title">
        <Endpoint
          method="DELETE"
          path={`${PATH_PREFIX}/instances/{id}/files?path=/path/to/target`}
          summaryKey="doc.sandbox.mutate.delete.summary"
        />
        <DocParagraph textKey="doc.sandbox.mutate.delete.p1" />

        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/rename`}
          summaryKey="doc.sandbox.mutate.rename.summary"
        />
        <DocSubSection titleKey="doc.sandbox.mutate.rename.body.title">
          <FieldTable
            rows={[
              { name: "fromPath", type: "string", required: true, descriptionKey: "doc.sandbox.mutate.rename.body.fromPath" },
              { name: "toPath", type: "string", required: true, descriptionKey: "doc.sandbox.mutate.rename.body.toPath" },
            ]}
          />
        </DocSubSection>

        <Endpoint
          method="POST"
          path={`${PATH_PREFIX}/instances/{id}/files/directory`}
          summaryKey="doc.sandbox.mutate.mkdir.summary"
        />
        <DocSubSection titleKey="doc.sandbox.mutate.mkdir.body.title">
          <FieldTable
            rows={[
              { name: "path", type: "string", required: true, descriptionKey: "doc.sandbox.mutate.mkdir.body.path" },
            ]}
          />
        </DocSubSection>
      </DocSection>
    </DocLayout>
  )
}
