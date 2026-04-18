"use client"

import { useState, useEffect, useMemo, useCallback, Suspense } from "react"
import { Plus, Search, Layers, LayoutGrid, ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { getColumns } from "./columns"
import { Application } from "@/lib/api/types"
import { getApplicationBuildConfig, getApplications } from "@/lib/api/applications"
import { useRouter, useSearchParams } from "next/navigation"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Input } from "@/components/ui/input"
import { ApplicationCreateDialog } from "./components/application-create-dialog"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { NamespaceParamProvider, useNamespace } from "@/contexts/namespace-context"

export default function AppsPage() {
  return (
    <Suspense>
      <NamespaceParamProvider>
        <AppsContent />
      </NamespaceParamProvider>
    </Suspense>
  )
}

function AppsContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const { namespaces, selectedNamespace, loadNamespaces } = useNamespace()

  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(false)
  const [applications, setApplications] = useState<Application[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const { t } = useLanguage()
  const columns = useMemo(() => getColumns(t), [t])

  const page = Number(searchParams.get("page") ?? "1")
  const size = Number(searchParams.get("size") ?? "10")

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
    if (selectedNamespace) {
      fetchData()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace, page, size])

  const handleNamespaceChange = (ns: string) => {
    updateParams({ namespace: ns, page: "1" })
  }

  const handleSearch = () => {
    updateParams({ page: "1" })
    fetchData()
  }

  const fetchData = async () => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }

    setLoading(true)
    try {
      const res = await getApplications(selectedNamespace, searchQuery || undefined, page, size)
      if (res.data) {
        setApplications(res.data.data)
        setTotalPages(res.data.totalPages)
      }
    } catch (error) {
      console.error("Failed to fetch applications:", error)
      toast.error(t("apps.fetchError"))
      setApplications([])
    } finally {
      setLoading(false)
    }
  }

  const handleEdit = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}`)
  }

  const handlePublish = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}/publish`)
  }

  const handleStatus = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}/status`)
  }

  const handlePipelines = (app: Application) => {
    router.push(`/pipelines?namespace=${app.namespace}&app=${app.name}`)
  }

  const handleIDE = async (app: Application) => {
    try {
      const res = await getApplicationBuildConfig(app.namespace, app.name)
      if (res.data?.sourceType === "ZIP") {
        toast.error(t("ide.zipUnsupported"))
        return
      }
      router.push(`/ides?namespace=${app.namespace}&app=${app.name}`)
    } catch {
      toast.error(t("ide.fetchError"))
    }
  }

  return (
    <ContentPage title={t("apps.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4 flex-wrap">
            <div className="flex items-center gap-4 flex-wrap">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Layers className="w-4 h-4" />{t("apps.namespaceFilter")}</span>
                <SelectWithSearch
                  value={selectedNamespace}
                  onValueChange={handleNamespaceChange}
                  options={namespaces.map(ns => ({ value: ns.id, label: ns.name }))}
                  placeholder={t("common.selectNamespace")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="w-4 h-4" />{t("apps.appNameFilter")}</span>
                <div className="flex w-full max-w-sm items-center space-x-2">
                  <Input
                    placeholder={t("apps.searchPlaceholder")}
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        handleSearch()
                      }
                    }}
                  />
                  <Button variant="outline" onClick={handleSearch}>
                    <Search className="h-4 w-4" />
                    {t("apps.searchBtn")}
                  </Button>
                </div>
              </div>
            </div>
            <Button onClick={() => setIsCreateOpen(true)}>
              <Plus className="h-4 w-4" />
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
              meta={{
                onEdit: handleEdit,
                onPublish: handlePublish,
                onStatus: handleStatus,
                onPipelines: handlePipelines,
                onIDE: handleIDE,
              }}
            />
            <div className="flex items-center justify-end gap-4 mt-2">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                <Select
                  value={String(size)}
                  onValueChange={(v) => updateParams({ size: v, page: "1" })}
                  disabled={loading}
                >
                  <SelectTrigger className="w-[70px] h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="10">10</SelectItem>
                    <SelectItem value="20">20</SelectItem>
                    <SelectItem value="50">50</SelectItem>
                  </SelectContent>
                </Select>
                <span className="text-sm text-muted-foreground">{t("common.pageSizeSuffix")}</span>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 1 || loading}
                  onClick={() => updateParams({ page: String(page - 1) })}
                >
                  <ChevronLeft className="h-4 w-4" />
                  {t("common.prevPage")}
                </Button>
                <span className="text-sm text-muted-foreground">
                  {t("common.pagePrefix")}{page}{t("common.pageSuffix")} / {t("common.totalPages").replace("${total}", String(totalPages))}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= totalPages || loading}
                  onClick={() => updateParams({ page: String(page + 1) })}
                >
                  {t("common.nextPage")}
                  <ChevronRight className="ml-2 h-4 w-4" />
                </Button>
              </div>
            </div>
          </>
        }
      />

      <ApplicationCreateDialog
        open={isCreateOpen}
        onOpenChange={setIsCreateOpen}
        namespaces={namespaces}
        defaultNamespace={selectedNamespace}
      />
    </ContentPage>
  )
}
