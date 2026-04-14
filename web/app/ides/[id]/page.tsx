"use client"

import { use } from "react"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import { useFeaturesStore } from "@/store/features"

export default function IDEInstancePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  const { t } = useLanguage()
  const { features, loaded } = useFeaturesStore()

  const url = features.ideHost
    ? `${features.ideHttps ? "https" : "http"}://${id}.${features.ideHost}`
    : ""

  return (
    <ContentPage title={id} className="gap-0">
      {url && loaded ? (
        <iframe
          src={url}
          className="flex-1 min-h-0 -mx-4 -mb-4 w-[calc(100%+2rem)] border-0"
          allow="clipboard-read; clipboard-write"
        />
      ) : (
        <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
          {t("ide.invalidUrl")}
        </div>
      )}
    </ContentPage>
  )
}
