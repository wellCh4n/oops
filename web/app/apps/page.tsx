"use client"

import { useState, useMemo, useEffect } from "react"
import { Plus, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { columns } from "./columns"
import { Application, Workspace } from "@/lib/api/types"
import { getApplications, getApplication, deleteApplication } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { useRouter, useSearchParams, usePathname } from "next/navigation"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Input } from "@/components/ui/input"

export default function AppsPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const pathname = usePathname()
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  
  const [selectedWorkspace, setSelectedWorkspace] = useState<string>("")
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(false)

  const [applications, setApplications] = useState<Application[]>([])

  // Fetch namespaces on mount
  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          const nsList = res.data.map((ns: string) => ({ id: ns, name: ns }))
          setWorkspaces(nsList)
          
          // Determine initial workspace: URL param > first namespace
          const workspaceParam = searchParams.get("workspace")
          let initialWorkspace = ""

          if (workspaceParam && nsList.some(ns => ns.id === workspaceParam)) {
            initialWorkspace = workspaceParam
          } else if (nsList.length > 0) {
            initialWorkspace = nsList[0].id
          }

          if (initialWorkspace) {
            setSelectedWorkspace(initialWorkspace)
            // Update URL if needed
            if (initialWorkspace !== workspaceParam) {
               const params = new URLSearchParams(searchParams)
               params.set("workspace", initialWorkspace)
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
  }, []) // Empty dependency array - run once on mount

  const handleWorkspaceChange = (value: string) => {
    setSelectedWorkspace(value)
    const params = new URLSearchParams(searchParams)
    params.set("workspace", value)
    router.replace(`${pathname}?${params.toString()}`)
  }

  // Fetch when workspace changes
  useEffect(() => {
    if (selectedWorkspace) {
      fetchData()
    }
  }, [selectedWorkspace])

  const handleSearch = () => {
    fetchData()
  }

  const fetchData = async () => {
    if (!selectedWorkspace) {
      setApplications([]) 
      return
    }

    setLoading(true)
    try {
      if (searchQuery) {
        // Search specific app
        try {
          const res = await getApplication(selectedWorkspace, searchQuery)
          if (res.data) {
             setApplications([res.data])
          } else {
             setApplications([])
          }
        } catch (error) {
          // If 404 or other error, clear list
          setApplications([])
        }
      } else {
        // List all apps
        const res = await getApplications(selectedWorkspace)
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
    router.push(`/apps/create?workspace=${selectedWorkspace}`)
  }

  const handleEdit = (app: Application) => {
    router.push(`/apps/${app.namespace}/${app.name}`)
  }

  const handleDelete = (id: string) => {
    // Note: We need namespace to delete, but interface might not have it or we assume selectedWorkspace
    // The columns.tsx passes ID.
    // Let's assume current workspace for now or fix types later.
    // Wait, the API deleteApplication needs namespace and id.
    // The `Application` object has `namespace`.
    // But `handleDelete` signature in `columns` is `(id: string) => void`.
    // I should update columns to pass application or update signature.
    // For now, let's just toast error if we can't delete properly.
    // Actually, I can fix `columns.tsx` to pass application to delete.
    // But standard table meta usually passes ID.
    // Let's rely on selectedWorkspace if app.namespace is missing or refetch.
    // Actually, `Application` has `namespace` field.
    // I will temporarily use selectedWorkspace, but ideally should use app's namespace.
    // Since we filter by workspace, app.namespace should be selectedWorkspace.
    deleteApplication(selectedWorkspace, id).then(() => {
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
    toast.info(`Checking status for ${app.name}...`)
    // TODO: Implement status logic
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4 flex-1">
          <Select value={selectedWorkspace} onValueChange={handleWorkspaceChange}>
            <SelectTrigger className="w-[200px]">
              <SelectValue placeholder="选择工作空间" />
            </SelectTrigger>
            <SelectContent>
              {workspaces.map(ws => (
                <SelectItem key={ws.id} value={ws.id}>{ws.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
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
