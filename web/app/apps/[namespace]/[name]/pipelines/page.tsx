"use client"

import { use, useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelines, stopPipeline } from "@/lib/api/pipelines"
import { Pipeline } from "@/lib/api/types"
import { getPipelineColumns } from "./columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { ArrowLeft, RotateCcw, ChevronLeft, ChevronRight } from "lucide-react"

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
  }>
}

export default function PipelinesPage({ params }: PageProps) {
  const router = useRouter()
  const { namespace, name } = use(params)
  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [hasNext, setHasNext] = useState(false)

  const fetchPipelines = async () => {
    setLoading(true)
    try {
      const res = await getPipelines(namespace, name, undefined, page, size)
      if (res.data) {
        setPipelines(res.data)
        setHasNext(res.data.length === size)
      }
    } catch (error) {
      toast.error("Failed to fetch pipelines")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchPipelines()
  }, [namespace, name, page, size])

  const handleView = (pipeline: Pipeline) => {
    router.push(`/apps/${namespace}/${name}/pipelines/${pipeline.id}`)
  }

  const handleStop = async (pipeline: Pipeline) => {
    try {
      await stopPipeline(namespace, name, pipeline.id)
      toast.success("Pipeline stop requested")
      fetchPipelines()
    } catch (error) {
      toast.error("Failed to stop pipeline")
    }
  }

  return (
    <div className="space-y-4 p-8">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" onClick={() => router.back()}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-2xl font-bold tracking-tight">流水线列表</h2>
        </div>
        <Button variant="outline" size="sm" onClick={fetchPipelines} disabled={loading}>
          <RotateCcw className={`mr-2 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </Button>
      </div>
      <DataTable columns={getPipelineColumns(handleView, handleStop)} data={pipelines} />
      <div className="flex items-center justify-end gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page === 0 || loading}
          onClick={() => setPage((p) => Math.max(0, p - 1))}
        >
          <ChevronLeft className="mr-2 h-4 w-4" />
          上一页
        </Button>
        <span className="text-sm text-muted-foreground">第 {page + 1} 页</span>
        <Button
          variant="outline"
          size="sm"
          disabled={!hasNext || loading}
          onClick={() => setPage((p) => p + 1)}
        >
          下一页
          <ChevronRight className="ml-2 h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
