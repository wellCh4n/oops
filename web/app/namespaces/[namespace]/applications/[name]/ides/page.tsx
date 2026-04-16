"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Link from "next/link"
import { useParams, useRouter, useSearchParams } from "next/navigation"
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
import { Button } from "@/components/ui/button"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { getApplications } from "@/lib/api/applications"
import { createIDE, deleteIDE, getDefaultIDEConfig, IDEInstance, listIDEs } from "@/lib/api/ide"
import { Application, ApplicationEnvironment } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"
import { useNamespaceStore } from "@/store/namespace"
import { useRecentAppStore } from "@/store/recent-app"
import { ApplicationEnvironmentSelector } from "@/app/apps/components/application-environment-selector"
import { applicationIdePath, applicationIdesPath, applicationsPath } from "@/lib/routes"
import { ChevronDown, ExternalLink, Layers, LayoutGrid, Plus, Power, RefreshCw } from "lucide-react"
import { toast } from "sonner"

export default function IDEPage() {
  const params = useParams()
  const router = useRouter()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const { t } = useLanguage()

  const namespaces = useNamespaceStore((state) => state.namespaces)
  const loadNamespaces = useNamespaceStore((state) => state.load)
  const setSelectedNamespace = useNamespaceStore((state) => state.setSelectedNamespace)
  const { recentApp, setRecentApp } = useRecentAppStore()

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [ides, setIdes] = useState<IDEInstance[]>([])
  const [loading, setLoading] = useState(false)

  const selectedEnv = searchParams.get("env") ?? ""
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [nameInput, setNameInput] = useState("")
  const [branch, setBranch] = useState("")
  const [settings, setSettings] = useState("")
  const [envVars, setEnvVars] = useState("")
  const [extensions, setExtensions] = useState("")
  const [creating, setCreating] = useState(false)
  const [configLoading, setConfigLoading] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<IDEInstance | null>(null)
  const [deleteConfirmText, setDeleteConfirmText] = useState("")
  const [deleting, setDeleting] = useState(false)
  const isDeleteConfirmed = deleteConfirmText.trim().toUpperCase() === "OK"

  const buildRoute = useCallback((targetNamespace: string, targetName: string, updates?: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates ?? {}).forEach(([key, value]) => {
      if (value) params.set(key, value)
      else params.delete(key)
    })
    const query = params.toString()
    const pathname = applicationIdesPath(targetNamespace, targetName)
    return query ? `${pathname}?${query}` : pathname
  }, [searchParams])

  const updateParams = useCallback((updates: Record<string, string>) => {
    router.replace(buildRoute(namespace, name, updates))
  }, [buildRoute, name, namespace, router])

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  useEffect(() => {
    loadNamespaces()
    setSelectedNamespace(namespace)
    setRecentApp({ namespace, name })
  }, [loadNamespaces, name, namespace, setRecentApp, setSelectedNamespace])

  useEffect(() => {
    if (!namespace) {
      setApplications([])
      return
    }
    getApplications(namespace)
      .then((res) => {
        setApplications(res.data?.data ?? [])
      })
      .catch(() => toast.error(t("apps.fetchError")))
  }, [namespace, t])

  useEffect(() => {
    if (name && !selectedEnv && environments.length > 0) {
      updateParams({ env: environments[0].environmentName })
    }
  }, [environments, name, selectedEnv, updateParams])

  const fetchIDEs = useCallback(async () => {
    if (!namespace || !name || !selectedEnv) return
    setLoading(true)
    try {
      const res = await listIDEs(namespace, name, selectedEnv)
      setIdes(res.data ?? [])
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [name, namespace, selectedEnv, t])

  useEffect(() => {
    if (selectedEnv) {
      fetchIDEs()
    } else {
      setIdes([])
    }
  }, [fetchIDEs, selectedEnv])

  useEffect(() => {
    stopPolling()
    if (ides.some((ide) => !ide.ready) && name && selectedEnv) {
      pollRef.current = setInterval(fetchIDEs, 5000)
    }
    return () => stopPolling()
  }, [fetchIDEs, ides, name, selectedEnv])

  const openCreateDialog = async () => {
    if (!namespace || !name || !selectedEnv) return
    setCreateOpen(true)
    setConfigLoading(true)
    try {
      const res = await getDefaultIDEConfig(namespace, name, selectedEnv)
      const raw = res.data?.settings ?? ""
      try {
        setSettings(JSON.stringify(JSON.parse(raw), null, 2))
      } catch {
        setSettings(raw)
      }
      setEnvVars(res.data?.env ?? "")
      setExtensions(res.data?.extensions ?? "")
    } catch {
      toast.error(t("ide.fetchError"))
    } finally {
      setConfigLoading(false)
    }
  }

  const handleCreate = async () => {
    if (!namespace || !name || !selectedEnv) return
    setCreating(true)
    try {
      await createIDE(namespace, name, selectedEnv, {
        name: nameInput.trim() || undefined,
        branch: branch.trim() || undefined,
        settings,
        env: envVars,
        extensions,
      })
      toast.success(t("ide.createSuccess"))
      setCreateOpen(false)
      setBranch("")
      setNameInput("")
      fetchIDEs()
    } catch {
      toast.error(t("ide.createError"))
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async () => {
    if (!namespace || !name || !selectedEnv || !deleteTarget) return
    setDeleting(true)
    try {
      await deleteIDE(namespace, name, deleteTarget.id, selectedEnv)
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

  const handleNamespaceChange = (targetNamespace: string) => {
    if (recentApp?.namespace === targetNamespace) {
      router.push(buildRoute(targetNamespace, recentApp.name, { env: "" }))
      return
    }
    router.push(applicationsPath(targetNamespace))
  }

  const handleApplicationChange = (targetName: string) => {
    const app = applications.find((item) => item.name === targetName)
    if (app) {
      setRecentApp({
        namespace: app.namespace,
        name: app.name,
        description: app.description,
        ownerName: app.ownerName,
      })
      router.push(buildRoute(app.namespace, app.name, { env: "" }))
    }
  }

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
                value={namespace}
                onValueChange={handleNamespaceChange}
                options={namespaces.map((ns) => ({ value: ns.id, label: ns.name }))}
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
                value={name}
                onValueChange={handleApplicationChange}
                options={applications.map((app) => ({ value: app.name, label: app.name }))}
                placeholder={t("ide.page.selectApp")}
                searchPlaceholder={t("common.search")}
                emptyText={t("common.noResults")}
                className="w-[200px]"
                disabled={!namespace}
              />
            </div>
          </div>
        }
        table={
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <ApplicationEnvironmentSelector
                  namespace={namespace}
                  applicationName={name}
                  value={selectedEnv}
                  onValueChange={(value) => updateParams({ env: value })}
                  onEnvironmentsLoaded={setEnvironments}
                />
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={fetchIDEs}
                  disabled={loading || !name || !selectedEnv}
                >
                  <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                  {t("pipelines.refresh")}
                </Button>
                <Button
                  size="sm"
                  onClick={openCreateDialog}
                  disabled={!namespace || !name || !selectedEnv}
                >
                  <Plus className="h-4 w-4" />
                  {t("ide.create")}
                </Button>
              </div>
            </div>

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
                            href={ide.ready ? applicationIdePath(namespace, name, ide.id) : "#"}
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
        }
      />

      <Dialog open={createOpen} onOpenChange={(open) => {
        setCreateOpen(open)
        if (!open) {
          setBranch("")
          setNameInput("")
        }
      }}>
        <DialogContent className="w-[80vw] grid-cols-1">
          <DialogHeader>
            <DialogTitle>{t("ide.create")}</DialogTitle>
            <DialogDescription>{name}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3 min-w-0">
            <Input
              value={nameInput}
              onChange={(e) => setNameInput(e.target.value)}
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
                        className="h-64 w-full font-mono text-sm resize-none break-all"
                      />
                    </TabsContent>
                  </Tabs>
                )}
              </CollapsibleContent>
            </Collapsible>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setCreateOpen(false)}>
                {t("common.cancel")}
              </Button>
              <Button onClick={handleCreate} disabled={creating}>
                {creating ? t("ide.creating") : t("common.confirm")}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => {
        if (!open) {
          setDeleteTarget(null)
          setDeleteConfirmText("")
        }
      }}>
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
            placeholder="OK"
          />
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleting || !isDeleteConfirmed}
            >
              {t("common.confirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
