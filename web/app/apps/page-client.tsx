"use client"

import { use, useState, useEffect, useMemo } from "react"
import { Plus, Search, Layers, LayoutGrid } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { getColumns } from "./columns"
import { Application } from "@/lib/api/types"
import { getApplications, getApplication } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { useRouter, usePathname } from "next/navigation"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Input } from "@/components/ui/input"
import { ApplicationCreateDialog } from "./components/application-create-dialog"
import { IDEDialog } from "./components/ide-dialog"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { useFeaturesStore } from "@/store/features"

export default function ClientApps({
  searchParams,
}: {
  searchParams: Promise<{ namespace?: string }>
}) {
  const router = useRouter()
  const pathname = usePathname()
  const params = use(searchParams)
  const namespaceParam = params.namespace

  const [namespaces, setNamespaces] = useState<{id: string, name: string}[]>([])
  const [selectedNamespace, setSelectedNamespace] = useState<string>("")
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(false)
  const [applications, setApplications] = useState<Application[]>([])
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [ideApp, setIdeApp] = useState<Application | null>(null)
  const ideEnabled = useFeaturesStore((s) => s.features.ide)
  const { t } = useLanguage()
  const columns = useMemo(() => getColumns(t), [t])

  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          const nsList = res.data.map((ns) => ({ id: ns.name, name: ns.name }))
          setNamespaces(nsList)

          let initialNamespace = ""
          if (namespaceParam && nsList.some(ns => ns.id === namespaceParam)) {
            initialNamespace = namespaceParam
          } else if (nsList.length > 0) {
            initialNamespace = nsList[0].id
          }

          if (initialNamespace) {
            setSelectedNamespace(initialNamespace)
            if (initialNamespace !== namespaceParam) {
              const params = new URLSearchParams(typeof window !== "undefined" ? window.location.search : "")
              params.set("namespace", initialNamespace)
              router.replace(`${pathname}?${params.toString()}`)
            }
          }
        }
      } catch (error) {
        console.error("Failed to fetch namespaces:", error)
        toast.error(t("apps.fetchNsError"))
      }
    }
    loadNamespaces()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleNamespaceChange = (value: string) => {
    setSelectedNamespace(value)
    const params = new URLSearchParams(typeof window !== "undefined" ? window.location.search : "")
    params.set("namespace", value)
    router.replace(`${pathname}?${params.toString()}`)
  }

  useEffect(() => {
    if (selectedNamespace) {
      fetchData()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  const handleSearch = () => {
    fetchData()
  }

  const fetchData = async () => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }

    setLoading(true)
    try {
      if (searchQuery) {
        try {
          const res = await getApplication(selectedNamespace, searchQuery)
          if (res.data) {
            setApplications([res.data])
          } else {
            setApplications([])
          }
        } catch {
          setApplications([])
        }
      } else {
        const res = await getApplications(selectedNamespace)
        setApplications(res.data)
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

  const handleIDE = (app: Application) => {
    setIdeApp(app)
  }

  return (
    <ContentPage title={t("apps.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4 flex-wrap">
            <div className="flex items-center gap-4 flex-wrap">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Layers className="w-4 h-4" />{t("apps.namespaceFilter")}</span>
                <Select value={selectedNamespace} onValueChange={handleNamespaceChange}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder={t("common.selectNamespace")} />
                  </SelectTrigger>
                  <SelectContent>
                    {namespaces.map(ns => (
                      <SelectItem key={ns.id} value={ns.id}>{ns.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
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
              ideEnabled,
            }}
          />
        }
      />

      <IDEDialog
        open={!!ideApp}
        onOpenChange={(o) => { if (!o) setIdeApp(null) }}
        application={ideApp}
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
