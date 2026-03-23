"use client"

import { useState, useEffect, useCallback, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelines, stopPipeline, deployPipeline } from "@/lib/api/pipelines"
import { getApplications, getApplicationBuildEnvConfigs } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { Pipeline, Application } from "@/lib/api/types"
import { getPipelineColumns } from "./columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { RotateCcw, ChevronLeft, ChevronRight } from "lucide-react"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"

export default function PipelinesPage() {
  return (
    <Suspense>
      <PipelinesContent />
    </Suspense>
  )
}

function PipelinesContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [hasNext, setHasNext] = useState(false)

  const [namespaces, setNamespaces] = useState<{id: string, name: string}[]>([])
  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<string[]>([])

  const [initialized, setInitialized] = useState(false)

  const selectedNamespace = searchParams.get("namespace") ?? ""
  const selectedApp = searchParams.get("app") ?? ""
  const selectedEnv = searchParams.get("env") ?? "all"
  const page = Number(searchParams.get("page") ?? "0")
  const size = 20

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([k, v]) => {
      if (v) params.set(k, v)
      else params.delete(k)
    })
    router.replace(`/pipelines?${params.toString()}`)
  }, [router, searchParams])

  // Load namespaces once
  useEffect(() => {
    const load = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          const nsList = res.data.map((ns) => ({ id: ns.name, name: ns.name }))
          setNamespaces(nsList)
          if (!searchParams.get("namespace") && nsList.length > 0) {
            updateParams({ namespace: nsList[0].id, page: "0" })
          }
        }
      } catch {
        toast.error("Failed to fetch namespaces")
      } finally {
        setInitialized(true)
      }
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Load applications when namespace changes
  useEffect(() => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        if (res.data) {
          setApplications(res.data)
          if (!searchParams.get("app") && res.data.length > 0) {
            updateParams({ app: res.data[0].name, page: "0" })
          }
        }
      } catch {
        toast.error("Failed to fetch applications")
        setApplications([])
      }
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  // Load environments when app changes
  useEffect(() => {
    if (!selectedNamespace || !selectedApp) {
      setEnvironments([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplicationBuildEnvConfigs(selectedNamespace, selectedApp)
        if (res.data) {
          setEnvironments(res.data.map(c => c.environmentName).filter((name): name is string => !!name))
        }
      } catch {
        setEnvironments([])
      }
    }
    load()
  }, [selectedNamespace, selectedApp])

  // Fetch pipelines
  const fetchPipelines = useCallback(async () => {
    if (!selectedNamespace || !selectedApp) {
      setPipelines([])
      return
    }
    setLoading(true)
    try {
      const res = await getPipelines(selectedNamespace, selectedApp, selectedEnv, page, size)
      if (res.data) {
        setPipelines(res.data)
        setHasNext(res.data.length === size)
      }
    } catch {
      toast.error("Failed to fetch pipelines")
    } finally {
      setLoading(false)
    }
  }, [selectedNamespace, selectedApp, selectedEnv, page, size])

  useEffect(() => {
    if (initialized) fetchPipelines()
  }, [initialized, fetchPipelines])

  const handleView = (pipeline: Pipeline) => {
    router.push(`/apps/${selectedNamespace}/${selectedApp}/pipelines/${pipeline.id}`)
  }

  const handleStop = async (pipeline: Pipeline) => {
    try {
      await stopPipeline(selectedNamespace, selectedApp, pipeline.id)
      toast.success("Pipeline stop requested")
      fetchPipelines()
    } catch {
      toast.error("Failed to stop pipeline")
    }
  }

  const handleDeploy = async (pipeline: Pipeline) => {
    try {
      await deployPipeline(selectedNamespace, selectedApp, pipeline.id)
      toast.success("已触发发布")
      fetchPipelines()
    } catch {
      toast.error("发布失败")
    }
  }

  return (
    <ContentPage title="流水线">
      <TableForm
        options={
          <div className="flex items-center justify-between flex-wrap gap-4">
            <div className="flex items-center gap-4 flex-wrap">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium whitespace-nowrap">命名空间:</span>
                <Select value={selectedNamespace} onValueChange={(v) => updateParams({ namespace: v, app: "", env: "all", page: "0" })}>
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
                <span className="text-sm font-medium whitespace-nowrap">应用:</span>
                <Select value={selectedApp} onValueChange={(v) => updateParams({ app: v, env: "all", page: "0" })} disabled={!selectedNamespace}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder="选择应用" />
                  </SelectTrigger>
                  <SelectContent>
                    {applications.map(app => (
                      <SelectItem key={app.name} value={app.name}>{app.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium whitespace-nowrap">环境:</span>
                <Select value={selectedEnv} onValueChange={(v) => updateParams({ env: v, page: "0" })} disabled={!selectedNamespace || !selectedApp}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder="选择环境" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">全部</SelectItem>
                    {environments.map(env => (
                      <SelectItem key={env} value={env}>{env}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <Button variant="outline" size="sm" onClick={fetchPipelines} disabled={loading || !selectedApp}>
              <RotateCcw className={`mr-2 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
              刷新
            </Button>
          </div>
        }
        table={
          <>
            <DataTable columns={getPipelineColumns(handleView, handleStop, handleDeploy)} data={pipelines} loading={loading} />
            {selectedApp && (
              <div className="flex items-center justify-end gap-2 mt-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 0 || loading}
                  onClick={() => updateParams({ page: String(page - 1) })}
                >
                  <ChevronLeft className="mr-2 h-4 w-4" />
                  上一页
                </Button>
                <span className="text-sm text-muted-foreground">第 {page + 1} 页</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!hasNext || loading}
                  onClick={() => updateParams({ page: String(page + 1) })}
                >
                  下一页
                  <ChevronRight className="ml-2 h-4 w-4" />
                </Button>
              </div>
            )}
          </>
        }
      />
    </ContentPage>
  )
}
