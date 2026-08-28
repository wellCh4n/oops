"use client"

import { Link2 } from "lucide-react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { createContext, ReactNode, useContext } from "react"
import { toast } from "sonner"
import { ContentPage } from "@/components/content-page"
import { DOC_TOPICS } from "@/components/doc/doc-topics"
import { DocProvider } from "@/components/doc/doc-context"
import { DocMarkup } from "@/components/doc/doc-markup"
import { anchorFromKey } from "@/components/doc/doc-anchor"
import { cn } from "@/lib/utils"
import { useLanguage } from "@/contexts/language-context"

const SectionSlugContext = createContext<string>("")

function AnchorHeading({
  id,
  as: Tag,
  className,
  children,
}: {
  id: string
  as: "h2" | "h3"
  className: string
  children: ReactNode
}) {
  const { t } = useLanguage()
  const handleCopy = async () => {
    const url = `${window.location.origin}${window.location.pathname}#${id}`
    try {
      await navigator.clipboard.writeText(url)
      toast.success(t("doc.anchorCopied"))
    } catch {
      window.location.hash = id
      toast.success(t("doc.anchorNavigated"))
    }
  }

  return (
    <Tag id={id} className={cn("group scroll-mt-14 flex items-center gap-2", className)}>
      <span>{children}</span>
      <button
        type="button"
        onClick={handleCopy}
        aria-label={t("doc.copyAnchor")}
        title={t("doc.copyAnchor")}
        className="inline-flex h-5 w-5 items-center justify-center rounded-sm text-muted-foreground opacity-0 transition-opacity hover:bg-muted hover:text-foreground group-hover:opacity-100 focus-visible:opacity-100 cursor-pointer"
      >
        <Link2 className="h-3.5 w-3.5" />
      </button>
    </Tag>
  )
}

interface DocLayoutProps {
  titleKey: string
  children: ReactNode
}

export function DocLayout({ titleKey, children }: DocLayoutProps) {
  const pathname = usePathname()
  const { t } = useLanguage()
  const title = t(titleKey)

  return (
    <ContentPage title={t("doc.title")} documentTitle={title}>
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
                    {t(topic.titleKey)}
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

export function DocSection({
  titleKey,
  id,
  children,
}: {
  titleKey: string
  id?: string
  children: ReactNode
}) {
  const { t } = useLanguage()
  const sectionId = id ?? anchorFromKey(titleKey)
  return (
    <SectionSlugContext.Provider value={sectionId}>
      <section className="space-y-3">
        <AnchorHeading id={sectionId} as="h2" className="border-b pb-1 text-lg font-semibold">
          {t(titleKey)}
        </AnchorHeading>
        {children}
      </section>
    </SectionSlugContext.Provider>
  )
}

export function DocSubSection({
  titleKey,
  id,
  children,
}: {
  titleKey: string
  id?: string
  children: ReactNode
}) {
  const { t } = useLanguage()
  const parentSlug = useContext(SectionSlugContext)
  const selfSlug = id ?? anchorFromKey(titleKey)
  // Key-derived slugs already carry the section path, so only prefix an
  // explicitly-passed `id` that doesn't sit under the parent already.
  const sectionId =
    !parentSlug || selfSlug.startsWith(`${parentSlug}-`) ? selfSlug : `${parentSlug}-${selfSlug}`
  return (
    <div className="space-y-2">
      <AnchorHeading id={sectionId} as="h3" className="text-sm font-semibold text-foreground/90">
        {t(titleKey)}
      </AnchorHeading>
      {children}
    </div>
  )
}

export function DocParagraph({ textKey }: { textKey: string }) {
  const { t } = useLanguage()
  return (
    <p className="text-sm leading-relaxed text-muted-foreground">
      <DocMarkup text={t(textKey)} />
    </p>
  )
}

export function DocList({ itemKeys }: { itemKeys: string[] }) {
  const { t } = useLanguage()
  return (
    <ul className="list-disc pl-5 text-sm text-muted-foreground space-y-1">
      {itemKeys.map((key) => (
        <li key={key}>
          <DocMarkup text={t(key)} />
        </li>
      ))}
    </ul>
  )
}
