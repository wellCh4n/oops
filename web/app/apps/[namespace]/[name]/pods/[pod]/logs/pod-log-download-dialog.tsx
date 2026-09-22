"use client"

import { useEffect, useState } from "react"
import { DownloadIcon } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { downloadPodLog, getPodLogRetention, PodLogRetention } from "@/lib/api/applications"
import { useLanguage } from "@/contexts/language-context"

interface Props {
  namespace: string
  name: string
  pod: string
  env: string
}

// A datetime-local input wants the viewer's wall clock without a zone; the Date it yields is
// then converted to an instant when the request is built.
const toLocalInputValue = (date: Date): string => {
  const pad = (value: number) => String(value).padStart(2, "0")
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// The file is named after the window the viewer picked, in their own wall clock, so
// `web-0_20260922-0900_20260922-1000.log` reads as the hour they asked for.
const toFileNameStamp = (date: Date): string => {
  const pad = (value: number) => String(value).padStart(2, "0")
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}`
}

export function PodLogDownloadDialog({ namespace, name, pod, env }: Props) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)
  const [since, setSince] = useState(() => toLocalInputValue(new Date(Date.now() - 60 * 60 * 1000)))
  const [until, setUntil] = useState(() => toLocalInputValue(new Date()))
  const [downloading, setDownloading] = useState(false)
  const [retention, setRetention] = useState<PodLogRetention | null>(null)

  // Read once per opening: the node's rotation is what bounds how far back the window can reach.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    getPodLogRetention(namespace, name, pod, env)
      .then((value) => { if (!cancelled) setRetention(value) })
      .catch(() => { if (!cancelled) setRetention({ maxFileSize: null, maxFiles: null }) })
    return () => { cancelled = true }
  }, [open, namespace, name, pod, env])

  const handleDownload = async () => {
    if (!since || !until) {
      toast.error(t("pods.downloadRangeRequired"))
      return
    }
    const sinceDate = new Date(since)
    const untilDate = new Date(until)
    if (untilDate < sinceDate) {
      toast.error(t("pods.downloadInvalidRange"))
      return
    }
    setDownloading(true)
    try {
      const { blob } = await downloadPodLog(namespace, name, pod, env, { since: sinceDate, until: untilDate })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement("a")
      anchor.href = url
      anchor.download = `${pod}_${toFileNameStamp(sinceDate)}_${toFileNameStamp(untilDate)}.log`
      document.body.appendChild(anchor)
      anchor.click()
      document.body.removeChild(anchor)
      URL.revokeObjectURL(url)
      setOpen(false)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("pods.downloadFailed"))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        render={
          <Button variant="outline" size="sm" className="cursor-pointer">
            <DownloadIcon className="size-4" />
            {t("pods.download")}
          </Button>
        }
      />
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("pods.download")}</DialogTitle>
          <DialogDescription>{t("pods.downloadDesc")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <div className="grid gap-2">
            <Label htmlFor="pod-log-since">{t("pods.downloadSince")}</Label>
            <Input id="pod-log-since" type="datetime-local" required value={since} onChange={(event) => setSince(event.target.value)} />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="pod-log-until">{t("pods.downloadUntil")}</Label>
            <Input id="pod-log-until" type="datetime-local" required value={until} onChange={(event) => setUntil(event.target.value)} />
          </div>
          <p className="text-xs text-muted-foreground">
            {retention === null
              ? t("pods.retentionLoading")
              : retention.maxFileSize
                ? t("pods.retentionHint")
                    .replaceAll("{size}", retention.maxFileSize)
                    .replace("{files}", String(retention.maxFiles))
                : t("pods.retentionUnknown")}
          </p>
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" className="cursor-pointer" onClick={() => setOpen(false)}>
            {t("common.cancel")}
          </Button>
          <Button type="button" className="cursor-pointer disabled:cursor-not-allowed" onClick={handleDownload} disabled={downloading}>
            {t("pods.downloadConfirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
