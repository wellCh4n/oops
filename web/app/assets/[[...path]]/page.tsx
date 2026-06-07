"use client"

import { Fragment, useCallback, useEffect, useMemo, useState } from "react"
import { useParams, useRouter } from "next/navigation"
import { FolderOpen, RefreshCw, Search, Upload } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataTable } from "@/components/ui/data-table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
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
import { useLanguage } from "@/contexts/language-context"
import { isAdmin } from "@/lib/auth"
import { AssetEntry, deleteEntry, fetchEntries } from "@/lib/api/assets"
import { getColumns } from "../columns"
import { AssetUploadDialog } from "../asset-upload-dialog"

export default function AssetsPage() {
  const { t } = useLanguage()
  const router = useRouter()
  const params = useParams()
  const segments = Array.isArray(params.path) ? params.path : []
  const path = segments.join("/")

  const [entries, setEntries] = useState<AssetEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [admin, setAdmin] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")
  const [uploadOpen, setUploadOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<AssetEntry | null>(null)
  const columns = useMemo(() => getColumns(t), [t])

  const setPath = useCallback((next: string) => {
    const nextSegments = next ? next.split("/").filter(Boolean) : []
    const url = nextSegments.length
      ? `/assets/${nextSegments.map(encodeURIComponent).join("/")}`
      : "/assets"
    router.replace(url)
  }, [router])

  useEffect(() => {
    setAdmin(isAdmin())
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchEntries(path)
      setEntries(data)
    } catch {
      toast.error(t("assets.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [path, t])

  useEffect(() => {
    void load()
  }, [load])

  const handleRefresh = useCallback(async () => {
    if (refreshing) return
    setRefreshing(true)
    // Keep the spinner visible long enough to read even when the fetch is instant.
    await Promise.all([load(), new Promise((resolve) => setTimeout(resolve, 600))])
    setRefreshing(false)
  }, [load, refreshing])

  const openFolder = (entry: AssetEntry) => {
    setSearch("")
    setAppliedSearch("")
    setPath(path ? `${path}/${entry.name}` : entry.name)
  }

  const navigateTo = (index: number) => {
    setSearch("")
    setAppliedSearch("")
    setPath(segments.slice(0, index + 1).join("/"))
  }

  const handleDelete = async () => {
    if (!deleteTarget) return
    try {
      await deleteEntry(deleteTarget.key)
      toast.success(t("assets.deleteSuccess"))
      setDeleteTarget(null)
      void load()
    } catch {
      toast.error(t("assets.deleteError"))
    }
  }

  const filtered = appliedSearch
    ? entries.filter((entry) => entry.name.toLowerCase().startsWith(appliedSearch.toLowerCase()))
    : entries

  return (
    <ContentPage title={t("assets.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <Search className="size-4" />
                {t("assets.col.fileName")}:
              </span>
              <div className="flex items-center gap-2">
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                  placeholder={t("assets.searchPlaceholder")}
                  className="w-56"
                />
                <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                  <Search className="size-4" />
                  {t("common.search")}
                </Button>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" onClick={handleRefresh} disabled={refreshing}>
                <RefreshCw className={`size-4 ${refreshing ? "animate-spin" : ""}`} />
                {t("assets.refresh")}
              </Button>
              <Button onClick={() => setUploadOpen(true)}>
                <Upload className="size-4" />
                {t("assets.uploadBtn")}
              </Button>
            </div>
          </div>
        }
        table={
          <>
            <div className="mb-4 flex items-center rounded-md border bg-muted/40 px-3 py-2">
              <Breadcrumb>
                <BreadcrumbList>
                  <BreadcrumbItem>
                    {segments.length === 0 ? (
                      <BreadcrumbPage className="flex items-center gap-1.5 font-medium">
                        <FolderOpen className="size-4 text-sky-500" />
                        {t("assets.title")}
                      </BreadcrumbPage>
                    ) : (
                      <BreadcrumbLink asChild>
                        <button
                          type="button"
                          onClick={() => navigateTo(-1)}
                          className="flex items-center gap-1.5"
                        >
                          <FolderOpen className="size-4 text-sky-500" />
                          {t("assets.title")}
                        </button>
                      </BreadcrumbLink>
                    )}
                  </BreadcrumbItem>
                  {segments.map((segment, index) => (
                    <Fragment key={`${segment}-${index}`}>
                      <BreadcrumbSeparator />
                      <BreadcrumbItem>
                        {index === segments.length - 1 ? (
                          <BreadcrumbPage className="font-medium">{segment}</BreadcrumbPage>
                        ) : (
                          <BreadcrumbLink asChild>
                            <button
                              type="button"
                              onClick={() => navigateTo(index)}
                            >
                              {segment}
                            </button>
                          </BreadcrumbLink>
                        )}
                      </BreadcrumbItem>
                    </Fragment>
                  ))}
                </BreadcrumbList>
              </Breadcrumb>
            </div>

            <DataTable
              columns={columns}
              data={filtered}
              loading={loading}
              meta={{
                onOpen: openFolder,
                onDelete: (entry: AssetEntry) => setDeleteTarget(entry),
                isAdmin: admin,
                t,
              }}
              getRowId={(row) => row.key}
            />
          </>
        }
      />

      <AssetUploadDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        path={path}
        onUploaded={load}
      />

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => { if (!open) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {deleteTarget?.type === "FOLDER" ? t("assets.deleteFolderTitle") : t("assets.deleteTitle")}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {deleteTarget?.type === "FOLDER" ? t("assets.deleteFolderDescPrefix") : t("assets.deleteDescPrefix")}
              <span className="font-medium">{deleteTarget?.name}</span>
              {deleteTarget?.type === "FOLDER" ? t("assets.deleteFolderDescSuffix") : t("assets.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete}>{t("assets.confirmDelete")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
