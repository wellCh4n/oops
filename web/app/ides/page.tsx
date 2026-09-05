"use client"

import { useState, useEffect, useRef, useCallback, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import Link from "next/link"
import {
  Plus, Power, ExternalLink, RefreshCw, ChevronDown, Layers, LayoutGrid,
} from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { Skeleton } from "@/components/ui/skeleton"
import { LocalTime } from "@/components/ui/local-time"
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from "@/components/ui/collapsible"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from "@/components/ui/dialog"
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { getApplicationBuildConfig, getApplications } from "@/lib/api/applications"
import { ALL_NAMESPACES, useNamespaces } from "@/hooks/use-namespaces"
import { ApplicationEnvironmentSelector } from "@/app/apps/components/application-environment-selector"
import { listIDEs, createIDE, deleteIDE, getDefaultIDEConfig, IDEInstance } from "@/lib/api/ide"
import { Application, ApplicationEnvironment, ApplicationSourceType } from "@/lib/api/types"
import { appIdentityBackground } from "@/lib/app-color"

export default function IDEPage() {
  return (
    <Suspense>
      <IDEPageContent />
    </Suspense>
  )
}

function IDEPageContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const { t } = useLanguage()
  const { namespaces } = useNamespaces()

  // Every filter lives in the URL and nowhere else. `?app=` is a bare name under a
  // concrete namespace and `namespace/name` under the "all" scope.
  const selectedNamespace = searchParams.get("namespace") || ALL_NAMESPACES
  const appParam = searchParams.get("app") ?? ""
  const appSeparator = appParam.indexOf("/")
  const selectedApp = appSeparator >= 0 ? appParam.slice(appSeparator + 1) : appParam
  const appNamespace = appSeparator >= 0 ? appParam.slice(0, appSeparator) : ""
  const urlEnv = searchParams.get("environment") ?? ""

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([key, value]) => {
      if (value) params.set(key, value)
      else params.delete(key)
    })
    const query = params.toString()
    router.replace(query ? `/ides?${query}` : "/ides")
  }, [router, searchParams])

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [ides, setIdes] = useState<IDEInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [sourceType, setSourceType] = useState<ApplicationSourceType>("GIT")

  const selectedApplication = applications.find(app => app.name === selectedApp
    && (!appNamespace || app.namespace === appNamespace))
  const selectedAppValue = selectedApplication?.id ?? selectedApp
  // The concrete namespace of the selected application: the scope itself unless
  // that is "all", in which case the URL carries it alongside the app name.
  const activeNamespace = selectedNamespace === ALL_NAMESPACES
    ? (appNamespace || selectedApplication?.namespace || "")
    : selectedNamespace
  // An environment the URL names is used if this app has it; otherwise the first
  // one, without rewriting the URL — only the user's own choice is written back.
  const activeEnv = environments.some((environment) => environment.environment === urlEnv)
    ? urlEnv
    : (environments[0]?.environment ?? "")

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // Create dialog state
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState("")
  const [branch, setBranch] = useState("")
  const [settings, setSettings] = useState("")
  const [envVars, setEnvVars] = useState("")
  const [extensions, setExtensions] = useState("")
  const [creating, setCreating] = useState(false)
  const [configLoading, setConfigLoading] = useState(false)

  // Delete state
  const [deleteTarget, setDeleteTarget] = useState<IDEInstance | null>(null)
  const [deleteConfirmText, setDeleteConfirmText] = useState("")
  const [deleting, setDeleting] = useState(false)

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  // The application dropdown's options. Nothing is auto-selected: with no app in
  // the URL the user picks one.
  useEffect(() => {
    const loadApps = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        setApplications(res.data?.data ?? [])
      } catch {
        toast.error(t("apps.fetchError"))
      }
    }
    loadApps()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  useEffect(() => {
    if (!activeNamespace || !selectedApp) {
      setSourceType("GIT")
      return
    }
    getApplicationBuildConfig(activeNamespace, selectedApp)
      .then((res) => {
        setSourceType(res.data?.sourceType || "GIT")
      })
      .catch(() => {
        setSourceType("GIT")
      })
  }, [activeNamespace, selectedApp])

  const fetchIDEs = useCallback(async () => {
    if (!activeNamespace || !selectedApp || !activeEnv) return
    setLoading(true)
    try {
      const res = await listIDEs(activeNamespace, selectedApp, activeEnv)
      setIdes(res.data ?? [])
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [activeNamespace, selectedApp, activeEnv, t])

  useEffect(() => {
    if (activeEnv) {
      fetchIDEs()
    } else {
      setIdes([])
    }
  }, [activeEnv, fetchIDEs])

  useEffect(() => {
    stopPolling()
    const hasPending = ides.some((ide) => !ide.ready)
    if (!hasPending || !selectedApp || !activeEnv) {
      return
    }
    const intervalId = setInterval(fetchIDEs, 5000)
    pollRef.current = intervalId
    return () => clearInterval(intervalId)
  }, [ides]) // eslint-disable-line react-hooks/exhaustive-deps

  const openCreateDialog = async () => {
    if (!activeNamespace || !selectedApp || !activeEnv) return
    if (sourceType === "ZIP") {
      toast.error(t("ide.zipUnsupported"))
      return
    }
    setCreateOpen(true)
    setConfigLoading(true)
    try {
      const res = await getDefaultIDEConfig(activeNamespace, selectedApp, activeEnv)
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
    if (!activeNamespace || !selectedApp || !activeEnv) return
    if (sourceType === "ZIP") {
      toast.error(t("ide.zipUnsupported"))
      return
    }
    setCreating(true)
    try {
      await createIDE(activeNamespace, selectedApp, activeEnv, {
        name: name.trim() || undefined,
        branch: branch.trim() || undefined,
        settings,
        env: envVars,
        extensions,
      })
      toast.success(t("ide.createSuccess"))
      setCreateOpen(false)
      setBranch("")
      setName("")
      fetchIDEs()
    } catch {
      toast.error(t("ide.createError"))
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async () => {
    if (!activeNamespace || !selectedApp || !activeEnv || !deleteTarget) return
    setDeleting(true)
    try {
      await deleteIDE(activeNamespace, selectedApp, deleteTarget.id, activeEnv)
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
    <ContentPage title={t("nav.ide")}>
      <TableForm
        options={
          <div className="flex items-center gap-4 flex-wrap">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <Layers className="size-4" />{t("apps.namespaceFilter")}
              </span>
              <SelectWithSearch
                value={selectedNamespace}
                // Changing the scope drops the app and env filters: they belonged to the old one.
                onValueChange={(namespace: string) => updateParams({ namespace: namespace === ALL_NAMESPACES ? "" : namespace, app: "", environment: "" })}
                options={[{ value: ALL_NAMESPACES, label: t("common.allNamespaces") }, ...namespaces.map((ns) => ({ value: ns.id, label: ns.name }))]}
                placeholder={t("common.selectNamespace")}
                searchPlaceholder={t("common.search")}
                emptyText={t("common.noResults")}
                className="w-[200px]"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <LayoutGrid className="size-4" />{t("apps.appNameFilter")}
              </span>
              <SelectWithSearch
                value={selectedAppValue}
                onOptionSelect={(option) => {
                  if (!option.namespace || !option.name) return
                  const appValue = selectedNamespace === ALL_NAMESPACES ? `${option.namespace}/${option.name}` : option.name
                  // The environment belongs to the app, so changing app resets it.
                  updateParams({ app: appValue, environment: "" })
                }}
                options={applications.map((app) => ({
                  value: app.id,
                  label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                  namespace: app.namespace,
                  name: app.name,
                  icon: app.icon,
                  colorBackground: appIdentityBackground(app),
                }))}
                onSearch={async (query) => {
                  const res = await getApplications(selectedNamespace, query || undefined, 1, 20)
                  return (res.data?.data ?? []).map(app => ({
                    value: app.id,
                    label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                    namespace: app.namespace,
                    name: app.name,
                    icon: app.icon,
                    colorBackground: appIdentityBackground(app),
                  }))
                }}
                placeholder={t("ide.page.selectApp")}
                searchPlaceholder={t("common.search")}
                emptyText={t("common.noResults")}
                className="w-[200px]"
              />
            </div>
          </div>
        }
        table={
          !selectedApp ? (
            <div className="py-16 text-center text-muted-foreground text-sm border rounded-md border-dashed">
              {t("ide.page.selectAppHint")}
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <ApplicationEnvironmentSelector
                    namespace={activeNamespace}
                    applicationName={selectedApp}
                    value={activeEnv}
                    onValueChange={(env) => updateParams({ environment: env })}
                    onEnvironmentsLoaded={setEnvironments}
                  />
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={fetchIDEs}
                    disabled={loading || !activeNamespace || !selectedApp || !activeEnv}
                  >
                    <RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />
                    {t("pipelines.refresh")}
                  </Button>
                  <Button
                    size="sm"
                    onClick={openCreateDialog}
                    disabled={!activeNamespace || !selectedApp || !activeEnv || sourceType === "ZIP"}
                  >
                    <Plus className="size-4" />
                    {t("ide.create")}
                  </Button>
                </div>
              </div>

              {sourceType === "ZIP" && (
                <div className="rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground">
                  {t("ide.zipUnsupportedDesc")}
                </div>
              )}

              {!activeEnv ? (
                environments.length === 0 ? null : (
                  <div className="py-8 text-center text-muted-foreground text-sm border rounded-md border-dashed">
                    {t("apps.envSelector.noEnv")}
                  </div>
                )
              ) : loading ? (
                <div className="space-y-2">
                  <div className="flex items-center justify-between rounded-md border px-3 py-2">
                    <div className="flex items-center gap-2">
                      <Skeleton className="size-2 rounded-full" />
                      <div className="flex flex-col gap-1">
                        <Skeleton className="h-4 w-32" />
                        <Skeleton className="h-3 w-48" />
                      </div>
                    </div>
                    <Skeleton className="size-8" />
                  </div>
                  <div className="flex items-center justify-between rounded-md border px-3 py-2">
                    <div className="flex items-center gap-2">
                      <Skeleton className="size-2 rounded-full" />
                      <div className="flex flex-col gap-1">
                        <Skeleton className="h-4 w-40" />
                        <Skeleton className="h-3 w-56" />
                      </div>
                    </div>
                    <Skeleton className="size-8" />
                  </div>
                  <div className="flex items-center justify-between rounded-md border px-3 py-2">
                    <div className="flex items-center gap-2">
                      <Skeleton className="size-2 rounded-full" />
                      <div className="flex flex-col gap-1">
                        <Skeleton className="h-4 w-28" />
                        <Skeleton className="h-3 w-44" />
                      </div>
                    </div>
                    <Skeleton className="size-8" />
                  </div>
                </div>
              ) : ides.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground text-sm border rounded-md border-dashed">
                  {t("ide.empty")}
                </div>
              ) : (
                <div className="space-y-2">
                  {ides.map((ide) => (
                    <div
                      key={ide.id}
                      className="flex items-center justify-between rounded-md border px-3 py-2 text-sm"
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <span
                          className={`shrink-0 size-2 rounded-full ${ide.ready ? "bg-success" : "bg-warning animate-pulse"}`}
                          title={ide.ready ? t("ide.statusReady") : t("ide.statusPending")}
                        />
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <div className="flex items-center gap-1.5 min-w-0">
                            <Link
                              // The detail page wants the concrete namespace and bare app name, not this
                              // page's scope filter, which under "all" spells the app as `namespace/name`.
                              href={ide.ready ? `/ides/${ide.id}?namespace=${encodeURIComponent(activeNamespace)}&app=${encodeURIComponent(selectedApp)}&environment=${encodeURIComponent(activeEnv)}` : "#"}
                              className={`font-mono text-sm truncate ${ide.ready ? "text-primary hover:underline cursor-pointer" : "text-muted-foreground cursor-not-allowed pointer-events-none"}`}
                            >
                              {ide.name}
                            </Link>
                            {ide.ready && (
                              <a
                                href={`${ide.https ? "https" : "http"}://${ide.host}`}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="shrink-0 text-muted-foreground hover:text-primary"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <ExternalLink className="size-3" />
                              </a>
                            )}
                          </div>
                          <span className="text-xs text-muted-foreground">
                            {ide.id !== ide.name && <>{ide.id} · </>}{ide.createdAt && <LocalTime value={ide.createdAt} />}
                          </span>
                        </div>
                      </div>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="text-destructive hover:text-destructive shrink-0"
                        onClick={() => setDeleteTarget(ide)}
                      >
                        <Power className="size-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        }
      />

      {/* Create Dialog */}
      <Dialog open={createOpen} onOpenChange={(o) => { setCreateOpen(o); if (!o) { setBranch(""); setName("") } }}>
        <DialogContent className="w-[80vw] grid-cols-1">
          <DialogHeader>
            <DialogTitle>{t("ide.create")}</DialogTitle>
            <DialogDescription>{selectedApp}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3 min-w-0">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("ide.namePlaceholder")}
            />
            <Input
              value={branch}
              onChange={(e) => setBranch(e.target.value)}
              placeholder={t("ide.branchPlaceholder")}
            />
            <Collapsible>
              <CollapsibleTrigger className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground [&[data-state=open]>svg]:rotate-180">
                <ChevronDown className="size-4 transition-transform duration-200" />
                {t("ide.advancedConfig")}
              </CollapsibleTrigger>
              <CollapsibleContent className="mt-2 min-w-0">
                {configLoading ? (
                  <Skeleton className="h-52 w-full" />
                ) : (
                  <Tabs defaultValue="env">
                    <TabsList className="w-full">
                      <TabsTrigger value="env" className="flex-1 cursor-pointer">{t("ide.tabEnv")}</TabsTrigger>
                      <TabsTrigger value="extensions" className="flex-1 cursor-pointer">{t("ide.tabExtensions")}</TabsTrigger>
                      <TabsTrigger value="settings" className="flex-1 cursor-pointer">{t("ide.tabSettings")}</TabsTrigger>
                    </TabsList>
                    <TabsContent value="env">
                      <Textarea
                        value={envVars}
                        onChange={(e) => setEnvVars(e.target.value)}
                        placeholder="KEY=VALUE"
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                    <TabsContent value="extensions">
                      <Textarea
                        value={extensions}
                        onChange={(e) => setExtensions(e.target.value)}
                        placeholder="anthropic.claude-code"
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                    <TabsContent value="settings">
                      <Textarea
                        value={settings}
                        onChange={(e) => setSettings(e.target.value)}
                        placeholder="{}"
                        className="h-40 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                  </Tabs>
                )}
              </CollapsibleContent>
            </Collapsible>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)} disabled={creating}>
              {t("common.cancel")}
            </Button>
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
            <AlertDialogDescription>{t("ide.deleteConfirmDesc")}</AlertDialogDescription>
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
              disabled={deleting || deleteConfirmText.toUpperCase() !== "OK"}
              className="!bg-destructive !text-destructive-foreground hover:!bg-destructive/90"
            >
              {deleting ? t("ide.deleting") : t("ide.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
