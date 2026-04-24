"use client"

import dynamic from "next/dynamic"
import { useParams, useSearchParams } from "next/navigation"
import { ContentPage } from "@/components/content-page"
import { useLanguage } from "@/contexts/language-context"

const TerminalView = dynamic(() => import("@/components/terminal-view"), {
  ssr: false,
  loading: () => <div className="p-4 text-white">Loading terminal...</div>
})

export default function TerminalPage() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("env")
  const { t } = useLanguage()

  if (!env) {
    return <div className="p-4">Missing env parameter</div>
  }

  return (
    <ContentPage
      title={t("apps.status.col.terminal")}
      disableGutter
      className="-m-4 w-[calc(100%+2rem)] gap-0 min-h-0 overflow-hidden self-stretch"
      bodyClassName="flex flex-1 min-h-0 flex-col pt-0 pb-0 overflow-hidden"
    >
      <TerminalView namespace={namespace} name={name} pod={pod} env={env} />
    </ContentPage>
  )
}
