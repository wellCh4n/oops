"use client"

import { useState, useEffect, useRef, useCallback, useMemo, Suspense } from "react"
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
import { NamespaceParamProvider, useNamespace } from "@/contexts/namespace-context"
import { getApplicationBuildConfig, getApplications } from "@/lib/api/applications"
import { useRecentAppStore } from "@/store/recent-app"
import { ApplicationEnvironmentSelector } from "@/app/apps/components/application-environment-selector"
import { listIDEs, createIDE, deleteIDE, getDefaultIDEConfig, IDEInstance } from "@/lib/api/ide"
import { Application, ApplicationEnvironment, ApplicationSourceType } from "@/lib/api/types"

export default function IDEPage() {
  return (
    <Suspense>
      <NamespaceParamProvider>
        <IDEPageContent />
      </NamespaceParamProvider>
    </Suspense>
  )
}

function IDEPageContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { t } = useLanguage()

  const { namespaces, selectedNamespace, loadNamespaces } = useNamespace()
  const { recentApp, setRecentApp } = useRecentAppStore()

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [ides, setIdes] = useState<IDEInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [sourceType, setSourceType] = useState<ApplicationSourceType>("GIT")

  const selectedApp = searchParams.get("app") ?? ""
  const selectedAppNamespace = searchParams.get("appNamespace") ?? ""
  const selectedEnv = searchParams.get("env") ?? ""

  const resolvedNamespace = useMemo(() => {
    if (selectedNamespace !== "all") return selectedNamespace
    if (selectedAppNamespace) return selectedAppNamespace
    const app = applications.find(a => a.name === selectedApp)
    return app?.namespace ?? ""
  }, [selectedNamespace, selectedApp, selectedAppNamespace, applications])

  const selectedAppValue = useMemo(() => {
    if (!selectedApp) return ""
    if (selectedNamespace !== "all") return selectedApp
    return resolvedNamespace ? `${resolvedNamespace}/${selectedApp}` : selectedApp
  }, [selectedNamespace, resolvedNamespace, selectedApp])

  const appOptionValue = useCallback((app: Application) => (
    selectedNamespace === "all" ? `${app.namespace}/${app.name}` : app.name
  ), [selectedNamespace])

  const resolveSelectedApp = useCallback((value: string) => {
    if (selectedNamespace !== "all") {
      return { namespace: selectedNamespace, name: value }
    }
    const separatorIndex = value.indexOf("/")
    if (separatorIndex > 0) {
      return {
        namespace: value.slice(0, separatorIndex),
        name: value.slice(separatorIndex + 1),
      }
    }
    const app = applications.find(a => a.name === value)
    return { namespace: app?.namespace ?? "", name: value }
  }, [selectedNamespace, applications])

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

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([k, v]) => {
      if (v) params.set(k, v)
      else params.delete(k)
    })
    router.replace(`/ides?${params.toString()}`)
  }, [router, searchParams])

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  useEffect(() => {
    loadNamespaces()
  }, [loadNamespaces])

  useEffect(() => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }
    const loadApps = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        const apps = res.data?.data ?? []
        setApplications(apps)
        if (!selectedApp && apps.length > 0) {
          const recent = recentApp
            ? apps.find(a => a.name === recentApp.name && a.namespace === recentApp.namespace)
            : undefined
          const targetApp = recent ?? apps[0]
          updateParams({
            app: targetApp.name,
            appNamespace: selectedNamespace === "all" ? targetApp.namespace : "",
            env: "",
          })
        }
      } catch {
        toast.error(t("apps.fetchError"))
      }
    }
    loadApps()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  useEffect(() => {
    if (selectedApp && !selectedEnv && environments.length > 0) {
      updateParams({ env: environments[0].environmentName })
    }
  }, [selectedApp, selectedEnv, environments, updateParams])

  useEffect(() => {
    if (!resolvedNamespace || !selectedApp) {
      setSourceType("GIT")
      return
    }
    getApplicationBuildConfig(resolvedNamespace, selectedApp)
      .then((res) => {
        setSourceType(res.data?.sourceType || "GIT")
      })
      .catch(() => {
        setSourceType("GIT")
      })
  }, [resolvedNamespace, selectedApp])

  const fetchIDEs = useCallback(async () => {
    if (!resolvedNamespace || !selectedApp || !selectedEnv) return
    setLoading(true)
    try {
      const res = await listIDEs(resolvedNamespace, selectedApp, selectedEnv)
      setIdes(res.data ?? [])
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [resolvedNamespace, selectedApp, selectedEnv, t])

  useEffect(() => {
    if (selectedEnv) {
      fetchIDEs()
    } else {
      setIdes([])
    }
  }, [selectedEnv, fetchIDEs])

  useEffect(() => {
    stopPolling()
    const hasPending = ides.some((ide) => !ide.ready)
    if (hasPending && selectedApp && selectedEnv) {
      pollRef.current = setInterval(fetchIDEs, 5000)
    }
    return () => stopPolling()
  }, [ides]) // eslint-disable-line react-hooks/exhaustive-deps

  const openCreateDialog = async () => {
    if (!resolvedNamespace || !selectedApp || !selectedEnv) return
    if (sourceType === "ZIP") {
      toast.error(t("ide.zipUnsupported"))
      return
    }
    setCreateOpen(true)
    setConfigLoading(true)
    try {
      const res = await getDefaultIDEConfig(resolvedNamespace, selectedApp, selectedEnv)
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
    if (!resolvedNamespace || !selectedApp || !selectedEnv) return
    if (sourceType === "ZIP") {
      toast.error(t("ide.zipUnsupported"))
      return
    }
    setCreating(true)
    try {
      await createIDE(resolvedNamespace, selectedApp, selectedEnv, {
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
    if (!resolvedNamespace || !selectedApp || !selectedEnv || !deleteTarget) return
    setDeleting(true)
    try {
      await deleteIDE(resolvedNamespace, selectedApp, deleteTarget.id, selectedEnv)
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

  const buildIDEHref = useCallback((id: string) => {
    const params = new URLSearchParams(searchParams.toString())
    if (resolvedNamespace) {
      params.set("namespace", resolvedNamespace)
      params.delete("appNamespace")
    }
    return `/ides/${id}${params.toString() ? `?${params.toString()}` : ""}`
  }, [resolvedNamespace, searchParams])

  return (
    <ContentPage title={t("nav.ide")}>
      <TableForm
        options={
          <div className="flex items-center gap-4 flex-wrap">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <Layers className="w-4 h-4" />{t("apps.namespaceFilter")}
              </span>
              <SelectWithSearch
                value={selectedNamespace}
                onValueChange={(v: string) => { updateParams({ namespace: v, app: "", appNamespace: "", env: "" }) }}
                options={[{ value: "all", label: t("common.allNamespaces") }, ...namespaces.map((ns) => ({ value: ns.id, label: ns.name }))]}
                placeholder={t("common.selectNamespace")}
                searchPlaceholder={t("common.search")}
                emptyText={t("common.noResults")}
                className="w-[200px]"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <LayoutGrid className="w-4 h-4" />{t("apps.appNameFilter")}
              </span>
              <SelectWithSearch
                value={selectedAppValue}
                onValueChange={(v: string) => {
                  const selected = resolveSelectedApp(v)
                  const app = applications.find(a => a.name === selected.name && a.namespace === selected.namespace)
                  if (app) {
                    setRecentApp({
                      namespace: app.namespace,
                      name: app.name,
                      description: app.description,
                      ownerName: app.ownerName,
                    })
                  }
                  updateParams({
                    app: selected.name,
                    appNamespace: selectedNamespace === "all" ? selected.namespace : "",
                    env: "",
                  })
                }}
                options={applications.map((app) => ({
                  value: appOptionValue(app),
                  label: selectedNamespace === "all" ? `${app.name} (${app.namespace})` : app.name,
                }))}
                onSearch={selectedNamespace ? async (query) => {
                  const res = await getApplications(selectedNamespace, query || undefined, 1, 20)
                  return (res.data?.data ?? []).map(app => ({
                    value: selectedNamespace === "all" ? `${app.namespace}/${app.name}` : app.name,
                    label: selectedNamespace === "all" ? `${app.name} (${app.namespace})` : app.name,
                  }))
                } : undefined}
                placeholder={t("ide.page.selectApp")}
                searchPlaceholder={t("common.search")}
                emptyText={t("common.noResults")}
                className="w-[200px]"
                disabled={!selectedNamespace}
              />
            </div>
          </div>
        }
        table={
          !selectedNamespace ? (
            <div className="py-16 text-center text-muted-foreground text-sm border rounded-md border-dashed">
              {t("ide.page.selectNsHint")}
            </div>
          ) : !selectedApp ? (
            <div className="py-16 text-center text-muted-foreground text-sm border rounded-md border-dashed">
              {t("ide.page.selectAppHint")}
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <ApplicationEnvironmentSelector
                    namespace={resolvedNamespace}
                    applicationName={selectedApp}
                    value={selectedEnv}
                    onValueChange={(v) => updateParams({ env: v })}
                    onEnvironmentsLoaded={setEnvironments}
                  />
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={fetchIDEs}
                    disabled={loading || !selectedApp || !selectedEnv}
                  >
                    <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                    {t("pipelines.refresh")}
                  </Button>
                  <Button
                    size="sm"
                    onClick={openCreateDialog}
                    disabled={!selectedNamespace || !selectedApp || !selectedEnv || sourceType === "ZIP"}
                  >
                    <Plus className="h-4 w-4" />
                    {t("ide.create")}
                  </Button>
                </div>
              </div>

              {sourceType === "ZIP" && (
                <div className="rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground">
                  {t("ide.zipUnsupportedDesc")}
                </div>
              )}

              {!selectedEnv ? (
                environments.length === 0 ? null : (
                  <div className="py-8 text-center text-muted-foreground text-sm border rounded-md border-dashed">
                    {t("apps.envSelector.noEnv")}
                  </div>
                )
              ) : loading ? (
                <div className="space-y-2">
                  <Skeleton className="h-12 w-full" />
                  <Skeleton className="h-12 w-full" />
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
                          className={`shrink-0 h-2 w-2 rounded-full ${ide.ready ? "bg-green-500" : "bg-yellow-400 animate-pulse"}`}
                          title={ide.ready ? t("ide.statusReady") : t("ide.statusPending")}
                        />
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <div className="flex items-center gap-1.5 min-w-0">
                            <Link
                              href={ide.ready ? buildIDEHref(ide.id) : "#"}
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
                                <ExternalLink className="h-3 w-3" />
                              </a>
                            )}
                          </div>
                          <span className="text-xs text-muted-foreground">
                            {ide.id !== ide.name && <>{ide.id} · </>}{ide.createdAt && new Date(ide.createdAt).toLocaleString()}
                          </span>
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
