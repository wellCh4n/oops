"use client"

import { useState, useEffect } from "react"
import { Plus, Trash2, ExternalLink } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog"
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
import { Skeleton } from "@/components/ui/skeleton"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { listIDEs, createIDE, deleteIDE, IDEInstance } from "@/lib/api/ide"
import { Application } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"

interface IDEDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  application: Application | null
}

export function IDEDialog({ open, onOpenChange, application }: IDEDialogProps) {
  const { t } = useLanguage()
  const [env, setEnv] = useState("")
  const [ides, setIdes] = useState<IDEInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<IDEInstance | null>(null)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    if (!open) {
      setEnv("")
      setIdes([])
    }
  }, [open])

  useEffect(() => {
    if (!env || !application) return
    fetchIDEs()
  }, [env, application]) // eslint-disable-line react-hooks/exhaustive-deps

  const fetchIDEs = async () => {
    if (!application || !env) return
    setLoading(true)
    try {
      const res = await listIDEs(application.namespace, application.name, env)
      setIdes(res.data ?? [])
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setLoading(false)
    }
  }

  const handleCreate = async () => {
    if (!application || !env) return
    setCreating(true)
    try {
      await createIDE(application.namespace, application.name, env)
      toast.success(t("ide.createSuccess"))
      fetchIDEs()
    } catch {
      toast.error(t("ide.createError"))
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async () => {
    if (!application || !env || !deleteTarget) return
    setDeleting(true)
    try {
      await deleteIDE(application.namespace, application.name, deleteTarget.name, env)
      toast.success(t("ide.deleteSuccess"))
      setDeleteTarget(null)
      fetchIDEs()
    } catch {
      toast.error(t("ide.deleteError"))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{t("ide.title")}</DialogTitle>
            <DialogDescription>{application?.name}</DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <ApplicationEnvironmentSelector
              namespace={application?.namespace}
              applicationName={application?.name}
              value={env}
              onValueChange={setEnv}
              onEnvironmentsLoaded={(envs) => {
                if (envs.length > 0 && !env) setEnv(envs[0].environmentName)
              }}
            />

            {env && (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{t("ide.instances")}</span>
                  <Button size="sm" onClick={handleCreate} disabled={creating}>
                    <Plus className="h-4 w-4" />
                    {creating ? t("ide.creating") : t("ide.create")}
                  </Button>
                </div>

                {loading ? (
                  <div className="space-y-2">
                    <Skeleton className="h-10 w-full" />
                    <Skeleton className="h-10 w-full" />
                  </div>
                ) : ides.length === 0 ? (
                  <div className="py-8 text-center text-muted-foreground text-sm border rounded-md border-dashed">
                    {t("ide.empty")}
                  </div>
                ) : (
                  <div className="space-y-2">
                    {ides.map((ide) => (
                      <div
                        key={ide.name}
                        className="flex items-center justify-between rounded-md border px-3 py-2 text-sm"
                      >
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <span className="font-mono text-xs text-muted-foreground truncate">{ide.name}</span>
                          <a
                            href={`${ide.https ? "https" : "http"}://${ide.host}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-center gap-1 text-primary hover:underline truncate"
                          >
                            <ExternalLink className="h-3 w-3 shrink-0" />
                            {ide.host}
                          </a>
                        </div>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive shrink-0"
                          onClick={() => setDeleteTarget(ide)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(o) => { if (!o) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("ide.deleteConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("ide.deleteConfirmDesc")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleting}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleting ? t("ide.deleting") : t("ide.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
