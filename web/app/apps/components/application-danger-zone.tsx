"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useLanguage } from "@/contexts/language-context"
import { deleteApplication } from "@/lib/api/applications"
import { toast } from "sonner"
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

interface ApplicationDangerZoneProps {
  namespace: string
  name: string
}

export function ApplicationDangerZone({ namespace, name }: ApplicationDangerZoneProps) {
  const { t } = useLanguage()
  const router = useRouter()
  const [showDialog, setShowDialog] = useState(false)
  const [confirmInput, setConfirmInput] = useState("")
  const [isDeleting, setIsDeleting] = useState(false)

  const handleDelete = async () => {
    if (confirmInput !== name) return
    setIsDeleting(true)
    let ok = false
    try {
      await deleteApplication(namespace, name)
      ok = true
      toast.success(t("apps.danger.deleteSuccess"))
      router.push("/apps")
    } catch (e) {
      console.error(e)
      toast.error(t("apps.danger.deleteError"))
    } finally {
      setIsDeleting(false)
      if (ok) {
        setShowDialog(false)
        setConfirmInput("")
      }
    }
  }

  return (
    <div className="w-full space-y-6">
      <div className="space-y-2">
        <h3 className="text-base font-medium text-red-500">{t("apps.danger.title")}</h3>
        <p className="text-sm text-muted-foreground">{t("apps.danger.desc")}</p>
      </div>

      <div className="flex items-center justify-between rounded-lg border border-red-200 dark:border-red-900 bg-red-50/50 dark:bg-red-950/20 p-4">
        <div className="space-y-1">
          <p className="text-sm font-medium">{t("apps.danger.deleteBtn")}</p>
          <p className="text-xs text-muted-foreground">
            {t("apps.danger.deleteDescPrefix")}
            <strong>{name}</strong>
            {t("apps.danger.deleteDescSuffix")}
          </p>
        </div>
        <Button
          variant="destructive"
          size="sm"
          onClick={() => {
            setConfirmInput("")
            setShowDialog(true)
          }}
        >
          <Trash2 className="h-4 w-4 mr-1" />
          {t("apps.danger.deleteBtn")}
        </Button>
      </div>

      <AlertDialog open={showDialog} onOpenChange={setShowDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.danger.deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("apps.danger.deleteDescPrefix")}
              <strong>{name}</strong>
              {t("apps.danger.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-2">
            <Input
              autoComplete="off"
              value={confirmInput}
              onChange={(e) => setConfirmInput(e.target.value)}
              placeholder={t("apps.danger.deletePlaceholder")}
              autoFocus
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel
              onClick={() => {
                setShowDialog(false)
                setConfirmInput("")
              }}
            >
              {t("common.cancel")}
            </AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={isDeleting || confirmInput !== name}
              onClick={(e) => {
                e.preventDefault()
                handleDelete()
              }}
            >
              {isDeleting ? t("common.loading") : t("apps.danger.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
