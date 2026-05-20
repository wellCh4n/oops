"use client"

import { ReactNode, useState } from "react"
import { Check, Copy } from "lucide-react"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { useDocContext } from "./doc-context"

type Method = "GET" | "POST" | "PUT" | "DELETE" | "PATCH"

const methodStyles: Record<Method, string> = {
  GET: "bg-emerald-100 text-emerald-800 dark:bg-emerald-500/20 dark:text-emerald-300",
  POST: "bg-sky-100 text-sky-800 dark:bg-sky-500/20 dark:text-sky-300",
  PUT: "bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300",
  DELETE: "bg-rose-100 text-rose-800 dark:bg-rose-500/20 dark:text-rose-300",
  PATCH: "bg-violet-100 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300",
}

interface EndpointProps {
  method: Method
  path: string
  summary?: ReactNode
}

function buildCurl(method: Method, url: string, token: string): string {
  const hasBody = method === "POST" || method === "PUT" || method === "PATCH"
  const lines = [
    `curl -X ${method} '${url}' \\`,
    `  -H 'Authorization: Bearer ${token}'`,
  ]
  if (hasBody) {
    lines[lines.length - 1] += " \\"
    lines.push("  -H 'Content-Type: application/json' \\")
    lines.push("  -d '<请求体>'")
  }
  return lines.join("\n")
}

export function Endpoint({ method, path, summary }: EndpointProps) {
  const { accessToken, baseUrl } = useDocContext()
  const [copied, setCopied] = useState(false)
  const token = accessToken ?? "$OOPS_TOKEN"
  const url = `${baseUrl}${path}`
  const curl = buildCurl(method, url, token)

  async function onCopy() {
    try {
      await navigator.clipboard.writeText(curl)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* ignore */
    }
  }

  return (
    <div className="rounded-md border bg-muted/30 p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span
          className={cn(
            "inline-flex items-center justify-center rounded px-2 py-0.5 text-xs font-semibold font-mono",
            methodStyles[method]
          )}
        >
          {method}
        </span>
        <code className="font-mono text-sm break-all">{path}</code>
      </div>
      {summary && <div className="text-sm text-muted-foreground">{summary}</div>}
      <div className="relative">
        <pre className="overflow-x-auto rounded border bg-background/60 p-2 pr-9 text-[11px] leading-relaxed font-mono">
          <code>{curl}</code>
        </pre>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="absolute right-1 top-1 size-6 text-muted-foreground hover:text-foreground"
          onClick={onCopy}
          aria-label="复制 curl"
        >
          {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
        </Button>
      </div>
    </div>
  )
}
