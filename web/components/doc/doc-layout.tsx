"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { ReactNode } from "react"
import { ContentPage } from "@/components/content-page"
import { DOC_TOPICS } from "@/components/doc/doc-topics"
import { DocProvider } from "@/components/doc/doc-context"
import { cn } from "@/lib/utils"

interface DocLayoutProps {
  title: string
  children: ReactNode
}

export function DocLayout({ title, children }: DocLayoutProps) {
  const pathname = usePathname()

  return (
    <ContentPage title="OpenAPI 文档" documentTitle={title}>
      <DocProvider>
        <div className="flex gap-6 items-start">
          <aside className="hidden md:block w-48 shrink-0 sticky top-14">
            <nav className="flex flex-col gap-0.5">
              {DOC_TOPICS.map((topic) => {
                const active = pathname === topic.href
                return (
                  <Link
                    key={topic.id}
                    href={topic.href}
                    className={cn(
                      "rounded-md px-3 py-1.5 text-sm transition-colors",
                      active
                        ? "bg-sidebar-accent text-sidebar-accent-foreground font-medium"
                        : "text-muted-foreground hover:bg-sidebar-accent/50 hover:text-foreground"
                    )}
                  >
                    {topic.title}
                  </Link>
                )
              })}
            </nav>
          </aside>
          <article className="flex-1 min-w-0 max-w-3xl space-y-6">
            <h1 className="text-2xl font-semibold">{title}</h1>
            {children}
          </article>
        </div>
      </DocProvider>
    </ContentPage>
  )
}

export function DocSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold border-b pb-1">{title}</h2>
      {children}
    </section>
  )
}

export function DocSubSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold text-foreground/90">{title}</h3>
      {children}
    </div>
  )
}

export function DocParagraph({ children }: { children: ReactNode }) {
  return <p className="text-sm leading-relaxed text-muted-foreground">{children}</p>
}
