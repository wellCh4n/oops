"use client"

import { useEffect, useRef, useState } from "react"
import { Upload } from "lucide-react"
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
} from "@/components/ui/dialog"
import { uploadAsset } from "@/lib/api/assets"
import { useLanguage } from "@/contexts/language-context"

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  path: string
  onUploaded: () => void
}

export function AssetUploadDialog({ open, onOpenChange, path, onUploaded }: Props) {
  const { t } = useLanguage()
  const [files, setFiles] = useState<FileList | null>(null)
  const [targetPath, setTargetPath] = useState(path)
  const [submitting, setSubmitting] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setTargetPath(path)
      return
    }
    setFiles(null)
    if (fileInputRef.current) fileInputRef.current.value = ""
  }, [open, path])

  const handleUpload = async () => {
    if (!files || files.length === 0) return
    setSubmitting(true)
    try {
      for (const file of Array.from(files)) {
        await uploadAsset(file, targetPath)
      }
      toast.success(t("assets.uploadSuccess"))
      onUploaded()
      onOpenChange(false)
    } catch {
      toast.error(t("assets.uploadError"))
    } finally {
      setSubmitting(false)
    }
  }

  const target = targetPath || "/"

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("assets.uploadTitle")}</DialogTitle>
          <DialogDescription>{t("assets.uploadDesc")}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="asset-path">{t("assets.field.path")}</Label>
            <Input
              id="asset-path"
              value={targetPath}
              onChange={(event) => setTargetPath(event.target.value)}
              placeholder={t("assets.field.pathPlaceholder")}
            />
            <span className="text-xs text-muted-foreground">
              {t("assets.uploadTarget")}
              <span className="ml-1 font-mono">{target}</span>
            </span>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="asset-file">{t("assets.field.file")}</Label>
            <Input
              id="asset-file"
              ref={fileInputRef}
              type="file"
              multiple
              onChange={(event) => setFiles(event.target.files)}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>
            {t("common.cancel")}
          </Button>
          <Button onClick={handleUpload} disabled={!files || files.length === 0 || submitting}>
            <Upload className="size-4" />
            {t("assets.uploadBtn")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
