"use client"

import { use, useState, useEffect } from "react"
import { Plus, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { columns } from "./columns"
import { Application } from "@/lib/api/types"
import { getApplications, getApplication, deleteApplication } from "@/lib/api/applications"
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

  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          const nsList = res.data.map((ns: string) => ({ id: ns, name: ns }))
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
        toast.error("Failed to fetch namespaces")
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
      toast.error("Failed to fetch applications")
      setApplications([])
    } finally {
      setLoading(false)
    }
  }

  const filteredApplications = applications

  const handleCreate = () => {
    router.push(`/apps/create?namespace=${selectedNamespace}`)
  }

  const handleEdit = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}`)
  }

  const handleDelete = (id: string) => {
    deleteApplication(selectedNamespace, id).then(() => {
      setApplications(applications.filter(app => app.id !== id))
      toast.success("Application deleted successfully")
    }).catch(() => {
      toast.error("Failed to delete application")
    })
  }

  const handlePublish = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}/publish`)
  }

  const handleStatus = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}/status`)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium whitespace-nowrap">命名空间:</span>
            <Select value={selectedNamespace} onValueChange={handleNamespaceChange}>
              <SelectTrigger className="w-[200px]">
                <SelectValue placeholder="选择命名空间" />
              </SelectTrigger>
              <SelectContent>
                {namespaces.map(ns => (
                  <SelectItem key={ns.id} value={ns.id}>{ns.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium whitespace-nowrap">应用名称:</span>
            <div className="flex w-full max-w-sm items-center space-x-2">
              <Input
                placeholder="搜索应用..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    handleSearch()
                  }
                }}
              />
              <Button size="icon" variant="ghost" onClick={handleSearch}>
                <Search className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
        <Button onClick={handleCreate}>
          <Plus className="mr-2 h-4 w-4" />
          创建应用
        </Button>
      </div>

      <DataTable
        columns={columns}
        data={filteredApplications}
        meta={{
          onEdit: handleEdit,
          onPublish: handlePublish,
          onStatus: handleStatus,
        }}
      />
    </div>
  )
}
