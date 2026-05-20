"use client"

import { cn } from "@/lib/utils"
import { useDocContext } from "./doc-context"

interface CodeBlockProps {
  children: string
  language?: string
  className?: string
}

const HOST_PLACEHOLDER = "https://oops.example.com"
const TOKEN_PLACEHOLDER = "$OOPS_TOKEN"

export function CodeBlock({ children, language, className }: CodeBlockProps) {
  const { accessToken, baseUrl } = useDocContext()
  const rendered = children
    .replaceAll(HOST_PLACEHOLDER, baseUrl || HOST_PLACEHOLDER)
    .replaceAll(TOKEN_PLACEHOLDER, accessToken ?? TOKEN_PLACEHOLDER)
  return (
    <div className={cn("relative", className)}>
      {language && (
        <div className="absolute right-2 top-1.5 text-[10px] font-mono uppercase text-muted-foreground select-none">
          {language}
        </div>
      )}
      <pre className="overflow-x-auto rounded-md border bg-muted/40 p-3 text-xs leading-relaxed [font-variant-ligatures:none] [font-feature-settings:'liga'_0,'clig'_0,'calt'_0]">
        <code className="font-mono">{rendered}</code>
      </pre>
    </div>
  )
}

export function InlineCode({ children }: { children: string }) {
  return (
    <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">{children}</code>
  )
}
