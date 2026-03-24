"use client"

import { use, useState, useEffect, useRef } from "react"
import { getPipeline, deployPipeline } from "@/lib/api/pipelines"
import { getApplicationStatus, getClusterDomain } from "@/lib/api/applications"
import { Pipeline, ApplicationPodStatus, ClusterDomainInfo } from "@/lib/api/types"
import { API_BASE_URL } from "@/lib/api/config"
import { getToken } from "@/lib/auth"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { DataTable } from "@/components/ui/data-table"
import { getStatusColumns } from "../../status/columns"
import { Rocket } from "lucide-react"
import { toast } from "sonner"
import dayjs from "dayjs"
import { ExternalLink, Check, ArrowUpRight } from "lucide-react"
import Link from "next/link"

const statusLabel: Record<string, string> = {
  BUILD_SUCCEEDED: "编译完成",
  INITIALIZED: "初始化",
  RUNNING: "运行中",
  DEPLOYING: "发布中",
  SUCCEEDED: "成功",
  ERROR: "失败",
  STOPPED: "已停止",
}

function getStatusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  if (status === "RUNNING" || status === "DEPLOYING") return "default"
  if (status === "SUCCEEDED") return "secondary"
  if (status === "ERROR" || status === "STOPPED") return "destructive"
  return "outline"
}

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
    pipelineId: string
  }>
}

export default function PipelineDetailPage({ params }: PageProps) {
  const { namespace, name, pipelineId } = use(params)
  const [pipeline, setPipeline] = useState<Pipeline | null>(null)
  const [logs, setLogs] = useState<string[]>([])
  const [steps, setSteps] = useState<string[]>([])
  const [activeStep, setActiveStep] = useState<string>("")
  const logContainerRef = useRef<HTMLDivElement>(null)

  const [podStatuses, setPodStatuses] = useState<ApplicationPodStatus[]>([])
  const [statusLoading, setStatusLoading] = useState(false)
  const [clusterDomain, setClusterDomain] = useState<ClusterDomainInfo | null>(null)


  const fetchPipeline = async () => {
    try {
      const res = await getPipeline(namespace, name, pipelineId)
      if (res.data) {
        setPipeline(res.data)
      }
    } catch {
      toast.error("Failed to fetch pipeline details")
    }
  }

  const handleDeploy = async () => {
    try {
      await deployPipeline(namespace, name, pipelineId)
      toast.success("已触发发布")
      fetchPipeline()
    } catch {
      toast.error("发布失败")
    }
  }

  useEffect(() => {
    fetchPipeline()
    const interval = setInterval(fetchPipeline, 5000)
    return () => clearInterval(interval)
  }, [namespace, name, pipelineId])

  // Poll application status when pipeline environment is known
  useEffect(() => {
    if (!pipeline?.environment) return

    const env = pipeline.environment

    setStatusLoading(true)
    getApplicationStatus(namespace, name, env)
      .then(res => setPodStatuses(res.data ?? []))
      .catch(() => setPodStatuses([]))
      .finally(() => setStatusLoading(false))

    getClusterDomain(namespace, name, env)
      .then(res => setClusterDomain(res.data ?? null))
      .catch(() => setClusterDomain(null))

    const intervalId = setInterval(() => {
      getApplicationStatus(namespace, name, env)
        .then(res => setPodStatuses(res.data ?? []))
        .catch(() => {})
    }, 1000)

    return () => clearInterval(intervalId)
  }, [namespace, name, pipeline?.environment])

  useEffect(() => {
    // Use WebSocket instead of SSE
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const baseUrl = API_BASE_URL.startsWith('http')
      ? API_BASE_URL.replace(/^http/, 'ws')
      : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

    const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pipelines/${pipelineId}/log?token=${getToken()}`
    let ws: WebSocket | null = null
    let heartbeatInterval: ReturnType<typeof setInterval> | null = null

    // Use a small timeout to prevent double connection in React Strict Mode
    const connectTimeout = setTimeout(() => {
      ws = new WebSocket(wsUrl)

      ws.onopen = () => {
        console.log("WebSocket connected for pipeline logs")
        heartbeatInterval = setInterval(() => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send("ping")
          }
        }, 10000)
      }

      ws.onmessage = (event) => {
        const message = event.data
        if (message === "pong") return

        try {
          // Try to parse as JSON
          const jsonData = JSON.parse(message)

          // Handle different message types based on type field
          if (jsonData.type === "steps") {
            // Handle steps list
            setSteps(jsonData.data as string[])
          } else if (jsonData.type === "step") {
            // Handle step log
            setLogs(prev => [...prev, jsonData.data as string])
            setActiveStep(jsonData.container as string)
          } else if (jsonData.type === "error") {
            // Handle error messages
            console.error("Pipeline error:", jsonData.data)
          } else {
            // Fallback for unknown types
            console.warn("Unknown message type:", jsonData.type)
          }
        } catch (e) {
          // Fallback for non-JSON messages (backward compatibility)
          if (message.startsWith("STEPS:")) {
            try {
              const stepNames = JSON.parse(message.substring(6)) as string[]
              setSteps(stepNames)
            } catch (parseError) {
              console.error("Failed to parse legacy steps format", parseError)
            }
          } else if (message.startsWith("ERROR:")) {
            console.error("Pipeline error:", message.substring(6))
          } else if (message.includes(":")) {
            // Legacy format: "stepName:logLine"
            const [stepName, ...logParts] = message.split(":")
            const logLine = logParts.join(":")
            setLogs(prev => [...prev, logLine])
            setActiveStep(stepName)
          } else {
            // Fallback for plain log messages
            setLogs(prev => [...prev, message])
          }
        }
      }

      ws.onerror = (e) => {
        console.error("WebSocket failed", e)
        if (ws) {
          ws.close()
        }
      }

      ws.onclose = () => {
        console.log("WebSocket connection closed")
        if (heartbeatInterval) clearInterval(heartbeatInterval)
      }
    }, 100)

    return () => {
      clearTimeout(connectTimeout)
      if (heartbeatInterval) clearInterval(heartbeatInterval)
      if (ws) {
        ws.close()
      }
    }
  }, [namespace, name, pipelineId])

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logs])

  const statusColumns = getStatusColumns(() => {}, () => {}, () => {}).filter(col => (col as { id?: string }).id !== "actions")

  const deployModeLabel = pipeline?.deployMode === "IMMEDIATE" ? "立即发布" : pipeline?.deployMode === "MANUAL" ? "手动发布" : null

  const activeIndex = steps.indexOf(activeStep)

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b pb-4">
          <div className="flex items-center gap-4">
            <div>
              <h2 className="text-xl font-bold">流水线详情</h2>
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                {pipeline && <>ID: <Badge variant="outline"><Copyable value={pipelineId} maxLength={Infinity} /></Badge></>}
                {pipeline && <>环境: <Badge variant="outline">{pipeline.environment}</Badge></>}
                {pipeline && <>状态: <Badge variant={getStatusVariant(pipeline.status)}>{statusLabel[pipeline.status] ?? pipeline.status}</Badge></>}
                {pipeline && <>创建时间: <Badge variant="outline">{dayjs(pipeline.createdTime).format('YYYY-MM-DD HH:mm:ss')}</Badge></>}
                {deployModeLabel && <>发布方式: <Badge variant="outline">{deployModeLabel}</Badge></>}
              </div>
            </div>
          </div>
          <div>
            {pipeline?.status === "BUILD_SUCCEEDED" && (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button variant="default" size="sm">
                    <Rocket className="mr-2 h-4 w-4" />
                    应用此发布
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>确认应用此发布？</AlertDialogTitle>
                    <AlertDialogDescription>
                      将把当前编译产物部署到环境 <strong>{pipeline.environment}</strong>，该操作无法撤销。
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>取消</AlertDialogCancel>
                    <AlertDialogAction onClick={handleDeploy}>确认发布</AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            )}
          </div>
        </div>

        {/* Content: logs left, status right */}
        <div className="flex-1 flex gap-4 overflow-hidden min-h-0">
            {/* Left column: steps + logs */}
            <div className="flex-1 flex flex-col gap-3 overflow-hidden min-h-0">
                {/* Steps Progress Bar */}
                {steps.length > 0 && (
                  <div className="flex items-start px-1 pt-1">
                    {steps.map((step, index) => {
                      const isCompleted = index < activeIndex
                      const isActive = index === activeIndex
                      return (
                        <div key={step} className="flex items-center flex-1 last:flex-none">
                          <div className="flex flex-col items-center gap-1 min-w-0">
                            <div className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium shrink-0
                              ${isCompleted ? 'bg-primary text-primary-foreground' : ''}
                              ${isActive ? 'bg-primary text-primary-foreground ring-2 ring-primary ring-offset-2' : ''}
                              ${!isCompleted && !isActive ? 'bg-muted text-muted-foreground' : ''}
                            `}>
                              {isCompleted ? <Check className="w-3 h-3" /> : index + 1}
                            </div>
                            <span className={`text-xs truncate max-w-24 text-center ${isActive ? 'text-foreground font-medium' : 'text-muted-foreground'}`}>
                              {step}
                            </span>
                          </div>
                          {index < steps.length - 1 && (
                            <div className={`flex-1 h-0.5 mb-5 mx-1 ${isCompleted ? 'bg-primary' : 'bg-muted'}`} />
                          )}
                        </div>
                      )
                    })}
                  </div>
                )}
                {/* Logs Area */}
                <div className="flex-1 bg-black text-white rounded-md p-4 font-mono text-sm overflow-hidden flex flex-col min-h-0">
                    <div ref={logContainerRef} className="flex-1 min-h-0 overflow-y-auto whitespace-pre-wrap break-all">
                        {logs.map((log, i) => (
                            <div key={i}>{log}</div>
                        ))}
                        {logs.length === 0 && <div className="text-gray-500">Waiting for logs...</div>}
                    </div>
                </div>
            </div>

            {/* Application Status (right) */}
            <div className="flex-1 border rounded-md p-4 flex flex-col gap-3 overflow-y-auto">
                <div className="flex items-center justify-between">
                    <h3 className="font-semibold">运行状态</h3>
                    {pipeline?.environment && (
                        <Link
                            href={`/apps/${namespace}/${name}/status?env=${pipeline.environment}`}
                            className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
                        >
                            查看详情
                            <ArrowUpRight className="h-3.5 w-3.5" />
                        </Link>
                    )}
                </div>
                {(clusterDomain?.internalDomain || clusterDomain?.externalDomain) && (
                  <div className="flex flex-col gap-1 text-sm">
                      {clusterDomain?.internalDomain && (
                          <div className="flex items-center gap-2">
                              <span className="font-medium">内部域名:</span>
                              <Copyable value={clusterDomain.internalDomain} maxLength={Infinity} />
                          </div>
                      )}
                      {clusterDomain?.externalDomain && (
                          <div className="flex items-center gap-2">
                              <span className="font-medium">外部域名:</span>
                              <Copyable value={clusterDomain.externalDomain} maxLength={Infinity} />
                              <a href={clusterDomain.externalDomain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                                  <ExternalLink className="h-4 w-4" />
                              </a>
                          </div>
                      )}
                  </div>
                )}
                <DataTable columns={statusColumns} data={podStatuses} loading={statusLoading} />
            </div>
        </div>

    </div>
  )
}
