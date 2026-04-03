"use client"

import { useState, useEffect, useRef } from "react"
import { Plus, Power, ExternalLink, RefreshCw, ChevronDown } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
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
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from "@/components/ui/collapsible"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { listIDEs, createIDE, deleteIDE, getDefaultIDEConfig, IDEInstance } from "@/lib/api/ide"
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

  // create dialog state
  const [createOpen, setCreateOpen] = useState(false)
  const [branch, setBranch] = useState("")
  const [settings, setSettings] = useState("")
  const [envVars, setEnvVars] = useState("")
  const [extensions, setExtensions] = useState("")
  const [creating, setCreating] = useState(false)
  const [configLoading, setConfigLoading] = useState(false)

  // delete state
  const [deleteTarget, setDeleteTarget] = useState<IDEInstance | null>(null)
  const [deleteConfirmText, setDeleteConfirmText] = useState("")
  const [deleting, setDeleting] = useState(false)

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  useEffect(() => {
    if (!open) {
      setEnv("")
      setIdes([])
      stopPolling()
    }
    return () => stopPolling()
  }, [open])

  useEffect(() => {
    if (!env || !application) return
    fetchIDEs()
  }, [env, application]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    stopPolling()
    const hasPending = ides.some((ide) => !ide.ready)
    if (hasPending && env && application) {
      pollRef.current = setInterval(fetchIDEs, 5000)
    }
    return () => stopPolling()
  }, [ides]) // eslint-disable-line react-hooks/exhaustive-deps

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

  const openCreateDialog = async () => {
    if (!application || !env) return
    setCreateOpen(true)
    setConfigLoading(true)
    try {
      const res = await getDefaultIDEConfig(application.namespace, application.name, env)
      const raw = res.data?.settings ?? ""
      try { setSettings(JSON.stringify(JSON.parse(raw), null, 2)) } catch { setSettings(raw) }
      setEnvVars(res.data?.env ?? "")
      setExtensions(res.data?.extensions ?? "")
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setConfigLoading(false)
    }
  }

  const handleCreate = async () => {
    if (!application || !env) return
    setCreating(true)
    try {
      await createIDE(application.namespace, application.name, env, {
        branch: branch.trim() || undefined,
        settings,
        env: envVars,
        extensions,
      })
      toast.success(t("ide.createSuccess"))
      setCreateOpen(false)
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
      setDeleteConfirmText("")
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
        <DialogContent className="w-[80vw]">
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
                  <span className="text-sm font-medium">{t("ide.instances")}（{ides.length}）</span>
                  <div className="flex items-center gap-2">
                    <Button size="sm" variant="outline" onClick={fetchIDEs} disabled={loading}>
                      <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                    </Button>
                    <Button size="sm" onClick={openCreateDialog} disabled={creating}>
                      <Plus className="h-4 w-4" />
                      {t("ide.create")}
                    </Button>
                  </div>
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
                  <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
                    {ides.map((ide) => (
                      <div
                        key={ide.name}
                        className="flex items-center justify-between rounded-md border px-3 py-2 text-sm"
                      >
                        <div className="flex items-center gap-2 min-w-0">
                          <span
                            className={`shrink-0 h-2 w-2 rounded-full ${ide.ready ? "bg-green-500" : "bg-yellow-400 animate-pulse"}`}
                            title={ide.ready ? t("ide.statusReady") : t("ide.statusPending")}
                          />
                          <div className="flex flex-col gap-0.5 min-w-0">
                            <a
                              href={ide.ready ? `${ide.https ? "https" : "http"}://${ide.host}` : undefined}
                              target="_blank"
                              rel="noopener noreferrer"
                              className={`flex items-center gap-1 font-mono text-sm truncate ${ide.ready ? "text-primary hover:underline cursor-pointer" : "text-muted-foreground cursor-not-allowed pointer-events-none"}`}
                            >
                              {ide.name}
                              <ExternalLink className="h-3 w-3 shrink-0" />
                            </a>
                            {ide.createdAt && (
                              <span className="text-xs text-muted-foreground">
                                {new Date(ide.createdAt).toLocaleString()}
                              </span>
                            )}
                          </div>
                        </div>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive shrink-0"
                          onClick={() => setDeleteTarget(ide)}
                        >
                          <Power className="h-4 w-4" />
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

      {/* Create Dialog */}
      <Dialog open={createOpen} onOpenChange={(o) => { setCreateOpen(o); if (!o) setBranch("") }}>
        <DialogContent className="w-[80vw] grid-cols-1">
          <DialogHeader>
            <DialogTitle>{t("ide.create")}</DialogTitle>
            <DialogDescription>{application?.name}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3 min-w-0">
            <Input
              value={branch}
              onChange={(e) => setBranch(e.target.value)}
              placeholder={t("ide.branchPlaceholder")}
            />
            <Collapsible>
              <CollapsibleTrigger className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground [&[data-state=open]>svg]:rotate-180">
                <ChevronDown className="h-4 w-4 transition-transform duration-200" />
                {t("ide.advancedConfig")}
              </CollapsibleTrigger>
              <CollapsibleContent className="mt-2 min-w-0">
                {configLoading ? (
                  <Skeleton className="h-52 w-full" />
                ) : (
                  <Tabs defaultValue="env">
                    <TabsList className="w-full">
                      <TabsTrigger value="env" className="flex-1">{t("ide.tabEnv")}</TabsTrigger>
                      <TabsTrigger value="extensions" className="flex-1">{t("ide.tabExtensions")}</TabsTrigger>
                      <TabsTrigger value="settings" className="flex-1">{t("ide.tabSettings")}</TabsTrigger>
                    </TabsList>
                    <TabsContent value="env">
                      <Textarea
                        value={envVars}
                        onChange={(e) => setEnvVars(e.target.value)}
                        placeholder={"KEY=VALUE"}
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                    <TabsContent value="extensions">
                      <Textarea
                        value={extensions}
                        onChange={(e) => setExtensions(e.target.value)}
                        placeholder={"anthropic.claude-code"}
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                    <TabsContent value="settings">
                      <Textarea
                        value={settings}
                        onChange={(e) => setSettings(e.target.value)}
                        placeholder={"{}"}
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                  </Tabs>
                )}
              </CollapsibleContent>
            </Collapsible>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)} disabled={creating}>{t("common.cancel")}</Button>
            <Button onClick={handleCreate} disabled={creating || configLoading}>
              {creating ? t("ide.creating") : t("common.create")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(o) => { if (!o) { setDeleteTarget(null); setDeleteConfirmText("") } }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("ide.deleteConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("ide.deleteConfirmDesc")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <Input
            value={deleteConfirmText}
            onChange={(e) => setDeleteConfirmText(e.target.value)}
            placeholder={t("ide.deleteConfirmPlaceholder")}
            className="mt-2"
          />
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleting || deleteConfirmText !== "OK"}
              className="!bg-destructive !text-destructive-foreground hover:!bg-destructive/90"
            >
              {deleting ? t("ide.deleting") : t("ide.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
