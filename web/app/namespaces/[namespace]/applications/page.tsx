"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import { ChevronLeft, ChevronRight, Layers, LayoutGrid, Plus, Search } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { DataTable } from "@/components/ui/data-table"
import { TableForm } from "@/components/ui/table-form"
import { ContentPage } from "@/components/content-page"
import { ApplicationCreateDialog } from "@/app/apps/components/application-create-dialog"
import { getColumns } from "@/app/apps/columns"
import { getApplications } from "@/lib/api/applications"
import { Application } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"
import { useNamespaceStore } from "@/store/namespace"
import {
  applicationIdesPath,
  applicationPath,
  applicationPipelinesPath,
  applicationPublishPath,
  applicationStatusPath,
  applicationsPath,
} from "@/lib/routes"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

export default function ApplicationsPage() {
  const params = useParams()
  const router = useRouter()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const namespaces = useNamespaceStore((state) => state.namespaces)
  const loadNamespaces = useNamespaceStore((state) => state.load)
  const setSelectedNamespace = useNamespaceStore((state) => state.setSelectedNamespace)

  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(false)
  const [applications, setApplications] = useState<Application[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const { t } = useLanguage()
  const columns = useMemo(() => getColumns(t), [t])

  const page = Number(searchParams.get("page") ?? "1")
  const size = Number(searchParams.get("size") ?? "10")

  const buildNamespaceUrl = useCallback((targetNamespace: string, updates?: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates ?? {}).forEach(([key, value]) => {
      if (value) params.set(key, value)
      else params.delete(key)
    })
    const query = params.toString()
    const pathname = applicationsPath(targetNamespace)
    return query ? `${pathname}?${query}` : pathname
  }, [searchParams])

  const updateParams = useCallback((updates: Record<string, string>) => {
    router.replace(buildNamespaceUrl(namespace, updates))
  }, [buildNamespaceUrl, namespace, router])

  useEffect(() => {
    loadNamespaces()
    setSelectedNamespace(namespace)
  }, [loadNamespaces, namespace, setSelectedNamespace])

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getApplications(namespace, searchQuery || undefined, page, size)
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
  }, [namespace, page, searchQuery, size, t])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleNamespaceChange = (targetNamespace: string) => {
    router.push(buildNamespaceUrl(targetNamespace, { page: "1" }))
  }

  const handleSearch = () => {
    updateParams({ page: "1" })
    fetchData()
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
                onEdit: (app: Application) => router.push(applicationPath(app.namespace, app.name)),
                onPublish: (app: Application) => router.push(applicationPublishPath(app.namespace, app.name)),
                onStatus: (app: Application) => router.push(applicationStatusPath(app.namespace, app.name)),
                onPipelines: (app: Application) => router.push(applicationPipelinesPath(app.namespace, app.name)),
                onIDE: (app: Application) => router.push(applicationIdesPath(app.namespace, app.name)),
              }}
            />
            <div className="flex items-center justify-end gap-4 mt-2">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                <Select
                  value={String(size)}
                  onValueChange={(value) => updateParams({ size: value, page: "1" })}
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
                  <ChevronRight className="h-4 w-4" />
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
        defaultNamespace={namespace}
      />
    </ContentPage>
  )
}
