"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { ArrowRightLeft, Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { useLanguage } from "@/contexts/language-context"
import { deleteApplication, migrateApplicationNamespace } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { Namespace } from "@/lib/api/types"
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

  const [namespaces, setNamespaces] = useState<Namespace[]>([])
  const [targetNamespace, setTargetNamespace] = useState("")
  const [showMigrateDialog, setShowMigrateDialog] = useState(false)
  const [migrateConfirmInput, setMigrateConfirmInput] = useState("")
  const [isMigrating, setIsMigrating] = useState(false)

  useEffect(() => {
    fetchNamespaces()
      .then((res) => {
        if (res.success && res.data) {
          setNamespaces(res.data.filter((ns) => ns.name !== namespace))
        }
      })
      .catch((e) => console.error(e))
  }, [namespace])

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

  const handleMigrate = async () => {
    if (migrateConfirmInput !== name || !targetNamespace) return
    setIsMigrating(true)
    let ok = false
    try {
      const res = await migrateApplicationNamespace(namespace, name, targetNamespace)
      if (!res.success) {
        toast.error(res.message || t("apps.danger.migrateError"))
        return
      }
      ok = true
      const failed = res.data?.failedEnvironments ?? []
      if (failed.length > 0) {
        toast.warning(`${t("apps.danger.migratePartial")}: ${failed.join(", ")}`)
      } else {
        toast.success(t("apps.danger.migrateSuccess"))
      }
      router.push(`/apps/${targetNamespace}/${name}?namespace=${targetNamespace}`)
    } catch (e) {
      console.error(e)
      toast.error(t("apps.danger.migrateError"))
    } finally {
      setIsMigrating(false)
      if (ok) {
        setShowMigrateDialog(false)
        setMigrateConfirmInput("")
      }
    }
  }

  return (
    <div className="w-full space-y-6">
      <div className="space-y-2">
        <h3 className="text-base font-medium text-destructive">{t("apps.danger.title")}</h3>
        <p className="text-sm text-muted-foreground">{t("apps.danger.desc")}</p>
      </div>

      <div className="flex items-center justify-between gap-4 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
        <div className="space-y-1">
          <p className="text-sm font-medium">{t("apps.danger.migrateBtn")}</p>
          <p className="text-xs text-muted-foreground">{t("apps.danger.migrateDesc")}</p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Select value={targetNamespace} onValueChange={setTargetNamespace}>
            <SelectTrigger className="w-44 cursor-pointer">
              <SelectValue placeholder={t("apps.danger.migrateSelectPlaceholder")} />
            </SelectTrigger>
            <SelectContent>
              {namespaces.map((ns) => (
                <SelectItem key={ns.name} value={ns.name} className="cursor-pointer">
                  {ns.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            variant="destructive"
            size="sm"
            disabled={!targetNamespace}
            onClick={() => {
              setMigrateConfirmInput("")
              setShowMigrateDialog(true)
            }}
          >
            <ArrowRightLeft className="size-4 mr-1" />
            {t("apps.danger.migrateBtn")}
          </Button>
        </div>
      </div>

      <div className="flex items-center justify-between rounded-lg border border-destructive/30 bg-destructive/5 p-4">
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
          <Trash2 className="size-4 mr-1" />
          {t("apps.danger.deleteBtn")}
        </Button>
      </div>

      <AlertDialog open={showMigrateDialog} onOpenChange={setShowMigrateDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.danger.migrateDialogTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("apps.danger.migrateDialogDescPrefix")}
              <strong>{name}</strong>
              {t("apps.danger.migrateDialogDescMiddle")}
              <strong>{targetNamespace}</strong>
              {t("apps.danger.migrateDialogDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-2">
            <Input
              autoComplete="off"
              value={migrateConfirmInput}
              onChange={(e) => setMigrateConfirmInput(e.target.value)}
              placeholder={t("apps.danger.migratePlaceholder")}
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel
              onClick={() => {
                setShowMigrateDialog(false)
                setMigrateConfirmInput("")
              }}
            >
              {t("common.cancel")}
            </AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={isMigrating || migrateConfirmInput !== name || !targetNamespace}
              onClick={(e) => {
                e.preventDefault()
                handleMigrate()
              }}
            >
              {isMigrating ? t("common.loading") : t("apps.danger.migrateConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

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
