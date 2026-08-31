"use client"

import { useState, useEffect, useMemo, useCallback, Suspense } from "react"
import { Plus, Search, Layers, LayoutGrid, User } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { applicationKey, getColumns } from "./columns"
import { ActiveDeployment, Application } from "@/lib/api/types"
import { getActiveDeployments, getApplications } from "@/lib/api/applications"
import { useRouter, useSearchParams } from "next/navigation"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { Pagination } from "@/components/ui/pagination"
import { Input } from "@/components/ui/input"
import { ApplicationCreateDialog } from "./components/application-create-dialog"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { useWorkContext } from "@/contexts/work-context"
import { ALL_NAMESPACES } from "@/store/work-context"
import { useOwnerFilterStore } from "@/store/owner-filter"
import { usePageSize } from "@/store/page-size"

// How often the "deploying" marks refresh. Only the deployment lookup is re-fetched — the
// applications themselves do not change on this cadence.
const DEPLOYING_POLL_INTERVAL_MS = 5000

export default function AppsPage() {
  return (
    <Suspense>
      <AppsContent />
    </Suspense>
  )
}

function AppsContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const { namespaces, loadNamespaces, namespace: selectedNamespace, selectNamespace } = useWorkContext()

  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(false)
  const [applications, setApplications] = useState<Application[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)
  const [activeDeployments, setActiveDeployments] = useState<Record<string, ActiveDeployment[]>>({})
  const { t } = useLanguage()
  const columns = useMemo(() => getColumns(t), [t])

  const page = Number(searchParams.get("page") ?? "1")
  const { size, rememberSize } = usePageSize(searchParams.get("size"))
  const ownerOnlyUrl = searchParams.get("ownerOnly")
  const { ownerOnly: ownerOnlyStore, setOwnerOnly } = useOwnerFilterStore()

  // URL has priority; fallback to store on first load
  const ownerOnly = ownerOnlyUrl !== null ? ownerOnlyUrl !== "false" : ownerOnlyStore

  // Stamp the remembered filter into the URL on first load when the param is absent.
  // This spells out the state the page is already in rather than navigating anywhere,
  // so it goes through the History API: no route transition, and no extra back-button
  // stop between the user and wherever they came from.
  useEffect(() => {
    if (ownerOnlyUrl === null && selectedNamespace) {
      const params = new URLSearchParams(searchParams.toString())
      params.set("ownerOnly", ownerOnlyStore ? "true" : "false")
      window.history.replaceState(null, "", `/apps?${params.toString()}`)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ownerOnlyUrl, selectedNamespace])

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([k, v]) => {
      if (v) params.set(k, v)
      else params.delete(k)
    })
    router.replace(`/apps?${params.toString()}`)
  }, [router, searchParams])

  // Load namespaces once
  useEffect(() => {
    loadNamespaces()
  }, [loadNamespaces])

  useEffect(() => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }
    // Namespace, page, size and owner filter all re-run this. Whichever request
    // returns last would otherwise win, so a superseded run drops its own reply
    // instead of painting the wrong namespace's rows under the current filters.
    let cancelled = false
    const fetchData = async () => {
      setLoading(true)
      try {
        const res = await getApplications(selectedNamespace, searchQuery || undefined, page, size, ownerOnly)
        if (cancelled) return
        if (res.data) {
          setApplications(res.data.data)
          setTotalPages(res.data.totalPages)
          setTotal(res.data.total)
        }
      } catch (error) {
        if (cancelled) return
        console.error("Failed to fetch applications:", error)
        toast.error(t("apps.fetchError"))
        setApplications([])
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchData()
    return () => { cancelled = true }
    // `searchQuery` is deliberately absent: the list reloads when the user presses
    // Search (which bumps `reloadToken`), not on every keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace, page, size, ownerOnly, reloadToken])

  // The deploying marks refresh on their own, independently of the application list: a poll
  // that fails is simply skipped, leaving the marks that are already on screen in place.
  const fetchActiveDeployments = useCallback(async () => {
    if (!selectedNamespace) {
      setActiveDeployments({})
      return
    }
    try {
      const res = await getActiveDeployments(selectedNamespace)
      const grouped: Record<string, ActiveDeployment[]> = {}
      for (const deployment of res.data ?? []) {
        const key = applicationKey(deployment.namespace, deployment.applicationName)
        ;(grouped[key] ??= []).push(deployment)
      }
      setActiveDeployments(grouped)
    } catch (error) {
      console.error("Failed to fetch active deployments:", error)
    }
  }, [selectedNamespace])

  useEffect(() => {
    if (!selectedNamespace) return
    fetchActiveDeployments()
    const intervalId = setInterval(() => {
      if (document.visibilityState === "visible") {
        fetchActiveDeployments()
      }
    }, DEPLOYING_POLL_INTERVAL_MS)
    return () => clearInterval(intervalId)
  }, [selectedNamespace, fetchActiveDeployments])

  // Searching the same term on the same page changes no dependency of the fetch
  // effect, so the button bumps this to ask for the run explicitly.
  const handleSearch = () => {
    updateParams({ page: "1" })
    setReloadToken((token) => token + 1)
  }

  return (
    <ContentPage title={t("apps.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4 flex-wrap">
            <div className="flex items-end gap-4 flex-wrap">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Layers className="size-4" />{t("apps.namespaceFilter")}</span>
                <SelectWithSearch
                  value={selectedNamespace}
                  onValueChange={(namespace: string) => selectNamespace(namespace, { page: "1" })}
                  options={[{ value: ALL_NAMESPACES, label: t("common.allNamespaces") }, ...namespaces.map(ns => ({ value: ns.id, label: ns.name }))]}
                  placeholder={t("common.selectNamespace")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><User className="size-4" />{t("apps.ownerFilter")}</span>
                <div className="inline-flex rounded-lg border bg-muted p-0.5 h-9">
                  <button
                    type="button"
                    onClick={() => {
                      setOwnerOnly(false)
                      updateParams({ ownerOnly: "false", page: "1" })
                    }}
                    className={`px-3 py-1 text-sm font-medium rounded-md transition-all cursor-pointer ${
                      !ownerOnly
                        ? "bg-background shadow-sm text-foreground"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    {t("apps.ownerAll")}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setOwnerOnly(true)
                      updateParams({ ownerOnly: "true", page: "1" })
                    }}
                    className={`px-3 py-1 text-sm font-medium rounded-md transition-all cursor-pointer ${
                      ownerOnly
                        ? "bg-background shadow-sm text-foreground"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    {t("apps.ownerMine")}
                  </button>
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="size-4" />{t("apps.appNameFilter")}</span>
                <Input
                  className="w-[200px]"
                  placeholder={t("apps.searchPlaceholder")}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      handleSearch()
                    }
                  }}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">&nbsp;</span>
                <Button variant="outline" onClick={handleSearch} className="h-9">
                  <Search className="size-4" />
                  {t("apps.searchBtn")}
                </Button>
              </div>
            </div>
            <Button onClick={() => setIsCreateOpen(true)}>
              <Plus className="size-4" />
              {t("apps.createBtn")}
            </Button>
          </div>
        }
        table={
          <>
            <DataTable
              columns={columns}
              data={applications}
              loading={loading}
              meta={{ activeDeployments }}
            />
            <Pagination
              page={page}
              totalPages={totalPages}
              total={total}
              onPageChange={(next) => updateParams({ page: String(next) })}
              size={size}
              onSizeChange={(next) => {
                rememberSize(next)
                updateParams({ size: String(next), page: "1" })
              }}
              loading={loading}
            />
          </>
        }
      />

      <ApplicationCreateDialog
        open={isCreateOpen}
        onOpenChange={setIsCreateOpen}
        namespaces={namespaces}
        defaultNamespace={selectedNamespace === ALL_NAMESPACES ? (namespaces[0]?.name ?? "") : selectedNamespace}
      />
    </ContentPage>
  )
}
