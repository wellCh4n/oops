"use client"

import { use, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { Rocket } from "lucide-react"
import { toast } from "sonner"
import { ContentPage } from "@/components/content-page"
import { useLanguage } from "@/contexts/language-context"
import { useFeaturesStore } from "@/store/features"
import { getApplication } from "@/lib/api/applications"
import { applicationPublishPath } from "@/lib/routes"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

export default function IDEInstancePage({
  params,
}: {
  params: Promise<{ namespace: string; name: string; id: string }>
}) {
  const { namespace, name, id } = use(params)
  const router = useRouter()
  const { t } = useLanguage()
  const { features, loaded } = useFeaturesStore()
  const [showConfirm, setShowConfirm] = useState(false)
  const [publishVisible, setPublishVisible] = useState(false)

  const url = features.ideHost
    ? `${features.ideHttps ? "https" : "http"}://${id}.${features.ideHost}`
    : ""

  useEffect(() => {
    getApplication(namespace, name)
      .then((res) => {
        if (res.data) {
          setPublishVisible(true)
        } else {
          setPublishVisible(false)
          toast.error(t("ide.publishAppNotFound"))
        }
      })
      .catch(() => {
        setPublishVisible(false)
        toast.error(t("ide.publishAppNotFound"))
      })
  }, [name, namespace, t])

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

      {publishVisible && (
        <button
          onClick={() => setShowConfirm(true)}
          className="fixed top-0.75 right-3 z-30 flex items-center gap-2 text-xs text-primary-foreground bg-primary px-3 py-1.5 rounded-full border border-primary-foreground/20 shadow-sm hover:bg-primary/90 transition-colors cursor-pointer"
        >
          <Rocket className="w-3.5 h-3.5" />
          <span>{t("ide.publish")}</span>
        </button>
      )}

      <AlertDialog open={showConfirm} onOpenChange={setShowConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("ide.publishConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("ide.publishConfirmDesc")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setShowConfirm(false)}>
              {t("common.cancel")}
            </AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setShowConfirm(false)
                router.push(applicationPublishPath(namespace, name))
              }}
            >
              {t("common.confirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
