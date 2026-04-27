"use client"

import { use, useState, useEffect, useRef, useMemo, useCallback, Fragment } from "react"
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
import { getPipelineStatusColumns } from "../columns"
import { toast } from "sonner"
import dayjs from "dayjs"
import { ExternalLink, Check, ArrowUpRight, RefreshCw, Rocket, WifiOff } from "lucide-react"
import Link from "next/link"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

// WebSocket message types
interface StepsMessage {
  type: "steps"
  data: string[]
}

interface StepLogMessage {
  type: "step"
  data: string
  container: string
}

interface ErrorMessage {
  type: "error"
  data: unknown
}

interface UnknownMessage {
  type: string
  data?: unknown
  container?: string
}

type PipelineMessage = StepsMessage | StepLogMessage | ErrorMessage | UnknownMessage

// Type guard functions
function isStepsMessage(msg: PipelineMessage): msg is StepsMessage {
  return msg.type === "steps" && Array.isArray(msg.data)
}

function isStepLogMessage(msg: PipelineMessage): msg is StepLogMessage {
  return msg.type === "step" && typeof msg.data === "string" && typeof msg.container === "string"
}

function isErrorMessage(msg: PipelineMessage): msg is ErrorMessage {
  return msg.type === "error"
}

const statusLabel: Record<string, string> = {
  BUILD_SUCCEEDED: "apps.pipeline.status.BUILD_SUCCEEDED",
  INITIALIZED: "apps.pipeline.status.INITIALIZED",
  RUNNING: "apps.pipeline.status.RUNNING",
  DEPLOYING: "apps.pipeline.status.DEPLOYING",
  SUCCEEDED: "apps.pipeline.status.SUCCEEDED",
  ERROR: "apps.pipeline.status.ERROR",
  STOPPED: "apps.pipeline.status.STOPPED",
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

  const [wsDisconnected, setWsDisconnected] = useState(false)
  const [podStatuses, setPodStatuses] = useState<ApplicationPodStatus[]>([])
  const [statusLoading, setStatusLoading] = useState(false)
  const [clusterDomain, setClusterDomain] = useState<ClusterDomainInfo | null>(null)
  const { t } = useLanguage()


  const fetchPipeline = useCallback(async () => {
    try {
      const res = await getPipeline(namespace, name, pipelineId)
      if (res.data) {
        setPipeline(res.data)
      }
    } catch {
      toast.error(t("apps.pipeline.fetchError"))
    }
  }, [namespace, name, pipelineId, t])

  const handleDeploy = async () => {
    try {
      await deployPipeline(namespace, name, pipelineId)
      toast.success(t("apps.pipeline.deploySuccess"))
      fetchPipeline()
    } catch {
      toast.error(t("apps.pipeline.deployError"))
    }
  }

  useEffect(() => {
    const interval = setInterval(fetchPipeline, 5000)
    const initialTimeout = setTimeout(fetchPipeline, 0)
    return () => {
      clearInterval(interval)
      clearTimeout(initialTimeout)
    }
  }, [fetchPipeline])

  // Poll application status when pipeline environment is known
  useEffect(() => {
    if (!pipeline?.environment) return

    const env = pipeline.environment
    const loadStatus = (showLoading = false) => {
      if (showLoading) setStatusLoading(true)
      getApplicationStatus(namespace, name, env)
        .then(res => setPodStatuses(res.data ?? []))
        .catch(() => setPodStatuses([]))
        .finally(() => { if (showLoading) setStatusLoading(false) })
      getClusterDomain(namespace, name, env)
        .then(res => setClusterDomain(res.data ?? null))
        .catch(() => setClusterDomain(null))
    }
    const intervalId = setInterval(() => loadStatus(false), 1000)
    const initialTimeout = setTimeout(() => loadStatus(true), 0)
    return () => {
      clearInterval(intervalId)
      clearTimeout(initialTimeout)
    }
  }, [namespace, name, pipeline?.environment])

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const baseUrl = API_BASE_URL.startsWith('http')
      ? API_BASE_URL.replace(/^http/, 'ws')
      : `${wsProtocol}//${window.location.host}${API_BASE_URL}`
    const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pipelines/${pipelineId}/log?token=${getToken()}`

    let ws: WebSocket | null = null
    let heartbeatInterval: ReturnType<typeof setInterval> | null = null
    let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
    let retryCount = 0
    const maxRetries = 5
    let unmounted = false
    // Set to true when server sends {"type":"done"} — no reconnect needed
    let streamDone = false

    const connect = () => {
      if (unmounted) return
      ws = new WebSocket(wsUrl)

      ws.onopen = () => {
        retryCount = 0
        heartbeatInterval = setInterval(() => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send("ping")
          }
        }, 10000)
      }

      ws.onmessage = (event) => {
        const message = event.data as string
        if (message === "pong") return
        if (message === "ping") {
          if (ws && ws.readyState === WebSocket.OPEN) ws.send("pong")
          return
        }

        try {
          const jsonData = JSON.parse(message) as PipelineMessage & { type: string }

          if (jsonData.type === "done") {
            streamDone = true
            return
          }

          if (isStepsMessage(jsonData)) {
            setSteps(jsonData.data)
          } else if (isStepLogMessage(jsonData)) {
            setLogs(prev => [...prev, jsonData.data])
            setActiveStep(jsonData.container)
          } else if (isErrorMessage(jsonData)) {
            setLogs(prev => [...prev, `[ERROR] ${jsonData.data}`])
          }
        } catch {
          // Fallback for non-JSON messages (backward compatibility)
          if (message.startsWith("STEPS:")) {
            try {
              const stepNames = JSON.parse(message.substring(6)) as string[]
              setSteps(stepNames)
            } catch (parseError) {
              console.error("Failed to parse legacy steps format", parseError)
            }
          } else if (message.startsWith("ERROR:")) {
            setLogs(prev => [...prev, `[ERROR] ${message.substring(6)}`])
          } else if (message.includes(":")) {
            const [stepName, ...logParts] = message.split(":")
            setLogs(prev => [...prev, logParts.join(":")])
            setActiveStep(stepName ?? "")
          } else {
            setLogs(prev => [...prev, message])
          }
        }
      }

      ws.onerror = () => {
        ws?.close()
      }

      ws.onclose = () => {
        if (heartbeatInterval) clearInterval(heartbeatInterval)
        if (unmounted || streamDone) return
        if (retryCount < maxRetries) {
          retryCount++
          const delay = Math.min(1000 * retryCount, 10000)
          reconnectTimeout = setTimeout(connect, delay)
        } else {
          setWsDisconnected(true)
        }
      }
    }

    // Small timeout to prevent double connection in React Strict Mode
    const initialTimeout = setTimeout(connect, 100)

    return () => {
      unmounted = true
      clearTimeout(initialTimeout)
      if (reconnectTimeout) clearTimeout(reconnectTimeout)
      if (heartbeatInterval) clearInterval(heartbeatInterval)
      if (ws) ws.close()
    }
  }, [namespace, name, pipelineId])

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logs])

  const statusColumns = useMemo(() => getPipelineStatusColumns(t, namespace, name, pipelineId), [t, namespace, name, pipelineId])

  const renderExpandedRow = (pod: ApplicationPodStatus) => {
    const containers = pod.containers ?? []
    return (
      <div className="flex flex-col gap-2">
        <div className="text-sm font-medium text-muted-foreground">
          {t("apps.status.containers")} ({containers.length})
        </div>
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="h-8 px-3">{t("apps.status.containerName")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerImage")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerReady")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerRestarts")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {containers.map((container) => (
                <TableRow key={container.name}>
                  <TableCell className="px-3 py-2 font-medium">{container.name}</TableCell>
                  <TableCell className="px-3 py-2 text-muted-foreground truncate max-w-xs">{container.image}</TableCell>
                  <TableCell className="px-3 py-2">
                    <Badge variant={container.ready ? "default" : "destructive"}>
                      {container.ready ? "Ready" : "Not Ready"}
                    </Badge>
                  </TableCell>
                  <TableCell className={container.restartCount > 0 ? "px-3 py-2 text-destructive font-medium" : "px-3 py-2"}>
                    {container.restartCount}
                  </TableCell>
                </TableRow>
              ))}
              {containers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="h-16 text-center text-muted-foreground">
                    {t("common.noData")}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    )
  }

  const deployModeLabel = pipeline?.deployMode === "IMMEDIATE" ? t("apps.pipeline.modeImmediate") : pipeline?.deployMode === "MANUAL" ? t("apps.pipeline.modeManual") : null

  const activeIndex = (pipeline?.status === "SUCCEEDED" || pipeline?.status === "BUILD_SUCCEEDED")
    ? steps.length
    : steps.indexOf(activeStep)

  return (
    <ContentPage title={t("apps.pipeline.title")} fullHeight>
      <div className="flex flex-1 min-h-0 flex-col gap-4">
        {wsDisconnected && (
          <div
            role="status"
            className="flex shrink-0 items-center justify-between gap-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-100"
          >
            <div className="flex min-w-0 items-center gap-2 text-sm">
              <WifiOff className="size-4 shrink-0" />
              <span className="truncate">{t("common.disconnected")}</span>
            </div>
            <Button
              variant="outline"
              size="xs"
              onClick={() => window.location.reload()}
              className="shrink-0 bg-background/80 text-foreground hover:bg-background"
            >
              <RefreshCw className="size-3" />
              {t("common.refresh")}
            </Button>
          </div>
        )}

        {/* Header */}
        <div className="flex items-center justify-between border-b pb-4">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              {pipeline && <>{t("apps.pipeline.id")} <Badge variant="outline"><Copyable value={pipelineId} maxLength={Infinity} /></Badge></>}
              {pipeline && <>{t("apps.pipeline.envLabel")} <Badge variant="outline">{pipeline.environment}</Badge></>}
              {pipeline && <>{t("apps.pipeline.statusLabel")} <Badge variant={getStatusVariant(pipeline.status)}>{t(statusLabel[pipeline.status] ?? pipeline.status)}</Badge></>}
              {pipeline && <>{t("apps.pipeline.createdAt")} <Badge variant="outline">{dayjs(pipeline.createdTime).format('YYYY-MM-DD HH:mm:ss')}</Badge></>}
              {deployModeLabel && <>{t("apps.pipeline.deployMode")} <Badge variant="outline">{deployModeLabel}</Badge></>}
              {pipeline?.operatorName && <>{t("apps.pipeline.operator")} <Badge variant="outline">{pipeline.operatorName}</Badge></>}
            </div>
          </div>
          <div>
            {pipeline?.status === "BUILD_SUCCEEDED" && (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button variant="default" size="sm">
                    <Rocket className="h-4 w-4" />
                    {t("apps.pipeline.deployBtn")}
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>{t("apps.pipeline.confirmTitle")}</AlertDialogTitle>
                    <AlertDialogDescription>
                      {t("apps.pipeline.confirmDescPrefix")}<strong>{pipeline.environment}</strong>{t("apps.pipeline.confirmDescSuffix")}
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
                    <AlertDialogAction onClick={handleDeploy}>{t("apps.pipeline.confirm")}</AlertDialogAction>
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
              <div className="flex items-start px-1 pt-1 pb-5">
                {steps.map((step, index) => {
                  const isCompleted = index < activeIndex
                  const isActive = index === activeIndex
                  return (
                    <div key={step} className="flex items-center flex-1 last:flex-none">
                      <div className="flex flex-col items-center relative w-6">
                        <div className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium shrink-0
                              ${isCompleted ? 'bg-primary text-primary-foreground' : ''}
                              ${isActive ? 'bg-primary text-primary-foreground ring-2 ring-primary ring-offset-2' : ''}
                              ${!isCompleted && !isActive ? 'bg-muted text-muted-foreground' : ''}
                            `}>
                          {isCompleted ? <Check className="w-3 h-3" /> : index + 1}
                        </div>
                        <span className={`absolute top-full mt-1 left-1/2 -translate-x-1/2 text-xs truncate max-w-24 text-center whitespace-nowrap ${isActive ? 'text-foreground font-medium' : 'text-muted-foreground'}`}>
                          {step}
                        </span>
                      </div>
                      {index < steps.length - 1 && (
                        <div className={`flex-1 h-0.5 mx-1 ${isCompleted ? 'bg-primary' : 'bg-muted'}`} />
                      )}
                    </div>
                  )
                })}
              </div>
            )}
            {/* Logs Area */}
            <div className="flex-1 bg-black text-white rounded-md p-4 font-mono text-sm overflow-hidden flex flex-col min-h-0">
              <div ref={logContainerRef} className="flex-1 min-h-0 overflow-auto whitespace-pre">
                {logs.map((log, i) => (
                  <div key={i} className={log.startsWith("[ERROR]") ? "text-red-400" : undefined}>{log}</div>
                ))}
                {logs.length === 0 && <div className="text-gray-500">Waiting for logs...</div>}
              </div>
            </div>
          </div>

          {/* Application Status (right) */}
          <div className="flex-1 border rounded-md p-4 flex flex-col gap-3 overflow-y-auto">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold">{t("apps.pipeline.runningStatus")}</h3>
              {pipeline?.environment && (
                <Link
                  href={`/apps/${namespace}/${name}/status?env=${pipeline.environment}`}
                  className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  {t("apps.pipeline.viewDetails")}
                  <ArrowUpRight className="h-3.5 w-3.5" />
                </Link>
              )}
            </div>
            {(clusterDomain?.internalDomain || (clusterDomain?.externalDomains && clusterDomain.externalDomains.length > 0)) && (
              <div className="flex flex-col gap-1 text-sm">
                {clusterDomain?.internalDomain && (
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{t("apps.pipeline.internalDomain")}</span>
                    <Copyable value={clusterDomain.internalDomain} maxLength={Infinity} />
                  </div>
                )}
                {clusterDomain?.externalDomains && clusterDomain.externalDomains.length > 0 && (
                  <div className="grid grid-cols-[auto_auto_auto] gap-x-2 gap-y-1 items-center w-fit">
                    {clusterDomain.externalDomains.map((domain, index) => (
                      <Fragment key={index}>
                        <span className="font-medium whitespace-nowrap">{index === 0 ? t("apps.pipeline.externalDomain") : ""}</span>
                        <Copyable value={domain} maxLength={Infinity} />
                        <a href={domain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                          <ExternalLink className="h-4 w-4" />
                        </a>
                      </Fragment>
                    ))}
                  </div>
                )}
              </div>
            )}
            <DataTable columns={statusColumns} data={podStatuses} loading={statusLoading} getRowId={(row) => row.name} renderExpandedRow={renderExpandedRow} />
          </div>
        </div>

      </div>
    </ContentPage>
  )
}
