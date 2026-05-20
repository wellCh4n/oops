import { cn } from "@/lib/utils"

interface CodeBlockProps {
  children: string
  language?: string
  className?: string
}

export function CodeBlock({ children, language, className }: CodeBlockProps) {
  return (
    <div className={cn("relative", className)}>
      {language && (
        <div className="absolute right-2 top-1.5 text-[10px] font-mono uppercase text-muted-foreground select-none">
          {language}
        </div>
      )}
      <pre className="overflow-x-auto rounded-md border bg-muted/40 p-3 text-xs leading-relaxed">
        <code className="font-mono">{children}</code>
      </pre>
    </div>
  )
}

export function InlineCode({ children }: { children: string }) {
  return (
    <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">{children}</code>
  )
}
