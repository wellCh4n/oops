"use client"

import { use, useState, useEffect, useRef, useMemo, useCallback, Fragment } from "react"
import { getPipeline, deployPipeline, stopPipeline, watchPipelineSteps, streamPipelineStepLog } from "@/lib/api/pipelines"
import { getApplicationStatus, getClusterDomain } from "@/lib/api/applications"
import { Pipeline, ApplicationPodStatus, ClusterDomainInfo, PipelineStepStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ConnectionLostBanner } from "@/components/connection-lost-banner"
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
import { getPipelineStatusColumns, imageTag } from "../columns"
import { toast } from "sonner"
import dayjs from "dayjs"
import { AlertTriangle, ExternalLink, Check, ArrowUpRight, Rocket, Ban, FileText, ChevronDown, Undo2, Loader2, X, Radio } from "lucide-react"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
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
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { shortImageName } from "@/lib/utils"
import { ApplicationEventsPanel } from "@/app/apps/components/application-events-panel"

interface LogRow {
  id: number
  text: string
  time?: string | null
}

// One step's log as received so far. `streamId` ties the rows to the subscription that produced
// them, so a batch from a stream the viewer already left can never land on the step they switched to.
interface StepLog {
  streamId: number
  step: string
  rows: LogRow[]
  error: string | null
}

const NO_ROWS: LogRow[] = []

// Which step the page shows when the viewer has not picked one: the step the build is on, else the
// one that failed, else the last one that ran. Nothing yet means the pod has not started a step.
function followedStepOf(steps: PipelineStepStatus[]): string | null {
  const running = steps.find((step) => step.state === "RUNNING")
  if (running) return running.name
  const failed = steps.find((step) => step.state === "FAILED")
  if (failed) return failed.name
  const lastSucceeded = [...steps].reverse().find((step) => step.state === "SUCCEEDED")
  return lastSucceeded?.name ?? null
}

function stepDuration(step: PipelineStepStatus): string {
  if (!step.startedAt || !step.finishedAt) return ""
  const seconds = Math.round((new Date(step.finishedAt).getTime() - new Date(step.startedAt).getTime()) / 1000)
  return Number.isNaN(seconds) || seconds < 0 ? "" : `${seconds}s`
}

// Kubernetes stamps each line in UTC; a build is read in the timezone of whoever is watching it.
// Clock time only — a build log is a sequence of moments inside one run, and the run's date is
// already on the page. An unparseable or absent stamp yields an empty string, which the caller
// reads as "this line has no time" — the line's own text is never dropped.
function formatLogTime(time?: string): string {
  if (!time) return ""
  const parsed = new Date(time)
  if (Number.isNaN(parsed.getTime())) return ""
  return parsed.toLocaleTimeString([], {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  })
}

const statusLabel: Record<string, string> = {
  BUILD_SUCCEEDED: "apps.pipeline.status.BUILD_SUCCEEDED",
  INITIALIZED: "apps.pipeline.status.INITIALIZED",
  RUNNING: "apps.pipeline.status.RUNNING",
  DEPLOYING: "apps.pipeline.status.DEPLOYING",
  ROLLING_OUT: "apps.pipeline.status.ROLLING_OUT",
  SUCCEEDED: "apps.pipeline.status.SUCCEEDED",
  ERROR: "apps.pipeline.status.ERROR",
  STOPPED: "apps.pipeline.status.STOPPED",
}

function getStatusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  if (status === "RUNNING" || status === "DEPLOYING" || status === "ROLLING_OUT") return "default"
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
  const [stepStatuses, setStepStatuses] = useState<PipelineStepStatus[]>([])
  const [buildPhase, setBuildPhase] = useState("Pending")
  const [stepsError, setStepsError] = useState<string | null>(null)
  // The step the viewer clicked, or null to follow whichever step the build is on.
  const [pinnedStep, setPinnedStep] = useState<string | null>(null)
  const [stepLog, setStepLog] = useState<StepLog | null>(null)
  const logIdRef = useRef(0)
  const streamIdRef = useRef(0)
  const logContainerRef = useRef<HTMLDivElement>(null)

  const [streamLost, setStreamLost] = useState(false)
  const [podStatuses, setPodStatuses] = useState<ApplicationPodStatus[]>([])
  const [statusLoading, setStatusLoading] = useState(false)
  const [errorLogsMenuOpen, setErrorLogsMenuOpen] = useState(false)
  const [clusterDomain, setClusterDomain] = useState<ClusterDomainInfo | null>(null)
  const { t } = useLanguage()

  // A rollback reuses a historic artifact and runs no build job, so it has neither steps nor build logs.
  const isRollback = pipeline?.triggerType === "ROLLBACK"
  const buildLogAvailable = pipeline !== null && !isRollback

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

  const [stopping, setStopping] = useState(false)
  const handleStop = async () => {
    setStopping(true)
    try {
      await stopPipeline(namespace, name, pipelineId)
      toast.success(t("pipelines.stopSuccess"))
      fetchPipeline()
    } catch {
      toast.error(t("pipelines.stopError"))
    } finally {
      setStopping(false)
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

  const visibleSteps = stepStatuses
  const buildFinished = buildPhase === "Succeeded" || buildPhase === "Failed"
  const followedStep = useMemo(() => followedStepOf(stepStatuses), [stepStatuses])
  const viewedStep = pinnedStep ?? followedStep
  const logRows = stepLog?.step === viewedStep ? stepLog.rows : NO_ROWS
  const logError = stepLog?.step === viewedStep ? stepLog.error : null

  // Follow the build's steps for as long as the build runs. This stream carries one status
  // snapshot per container transition and no log lines, so it is cheap to hold open.
  useEffect(() => {
    if (!buildLogAvailable) return
    return watchPipelineSteps(namespace, name, pipelineId, {
      onSteps: (names) =>
        setStepStatuses((prev) => (prev.length > 0 ? prev : names.map((stepName) => ({ name: stepName, state: "PENDING" as const })))),
      onStatus: (snapshot) => {
        setStepStatuses(snapshot.steps)
        setBuildPhase(snapshot.phase)
      },
      onError: setStepsError,
      onTerminate: () => setStreamLost(true),
    })
  }, [namespace, name, pipelineId, buildLogAvailable])

  // One log stream at a time, for the step being viewed. Switching steps closes the old stream
  // and opens a new one; the server replays a finished step at once and follows a running one.
  useEffect(() => {
    if (!buildLogAvailable || !viewedStep) return
    const step = viewedStep
    const streamId = ++streamIdRef.current
    return streamPipelineStepLog(namespace, name, pipelineId, step, {
      onLog: (batch) =>
        setStepLog((prev) => {
          const rows = batch.lines.map((line) => ({ id: ++logIdRef.current, text: line.text, time: line.time }))
          return prev?.streamId === streamId
            ? { ...prev, rows: prev.rows.concat(rows) }
            : { streamId, step, rows, error: null }
        }),
      onError: (message) =>
        setStepLog((prev) => (prev?.streamId === streamId ? { ...prev, error: message } : { streamId, step, rows: [], error: message })),
      onTerminate: () => setStreamLost(true),
    })
  }, [namespace, name, pipelineId, buildLogAvailable, viewedStep])

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logRows])

  // A rollback's artifact is its source's image, so its tag is the source's id — the pod running it
  // is the right version, and comparing against this pipeline's own id would call it the wrong one.
  // Before a build produces an artifact the pipeline's own id is the tag it is going to get.
  const expectedTag = imageTag(pipeline?.artifact) || pipelineId
  const statusColumns = useMemo(() => getPipelineStatusColumns(t, namespace, name, expectedTag), [t, namespace, name, expectedTag])

  const crashLoopPods = useMemo(
    () =>
      podStatuses.filter((pod) =>
        (pod.containers ?? []).some((container) => container.reason === "CrashLoopBackOff")
      ),
    [podStatuses]
  )

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
                  <TableCell className="px-3 py-2 text-muted-foreground max-w-xs">
                    <Tooltip>
                      <TooltipTrigger render={<span className="truncate inline-block max-w-full align-bottom" />}>
                        {shortImageName(container.image)}
                      </TooltipTrigger>
                      <TooltipContent className="max-w-160 break-all font-mono text-xs">
                        {container.image}
                      </TooltipContent>
                    </Tooltip>
                  </TableCell>
                  <TableCell className="px-3 py-2">
                    <Badge variant={container.ready ? "info" : "destructive"}>
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

  const applicationEventSince = pipeline?.createdTime ? dayjs(pipeline.createdTime).toISOString() : undefined

  return (
    <ContentPage title={t("apps.pipeline.title")} fullHeight>
      <div className="flex flex-1 min-h-0 flex-col gap-4">
        {streamLost && (
          <ConnectionLostBanner
            className="rounded-md"
            message={t("common.disconnected")}
            retryLabel={t("common.refresh")}
          />
        )}

        {pipeline?.status === "ERROR" && pipeline.message && (
          <div
            role="alert"
            className="flex shrink-0 items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-foreground"
          >
            <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" />
            <div className="min-w-0">
              <div className="font-medium">{t("apps.pipeline.message")}</div>
              <div className="mt-1 flex flex-wrap items-center gap-2">
                <span className="whitespace-pre-wrap break-words">{pipeline.message}</span>
                {crashLoopPods.length === 1 && (
                  <Button render={<Link href={`/apps/${namespace}/${name}/pods/${crashLoopPods[0].name}/logs?environment=${encodeURIComponent(pipeline.environment)}`} />} variant="destructive" size="xs" className="shrink-0">
                    <FileText className="size-3" />
                    {t("apps.pipeline.viewLogs")}
                  </Button>
                )}
                {crashLoopPods.length > 1 && (
                  <Popover open={errorLogsMenuOpen} onOpenChange={setErrorLogsMenuOpen}>
                    <PopoverTrigger render={<Button
                        variant="destructive"
                        size="xs"
                        className="shrink-0"
                        onMouseEnter={() => setErrorLogsMenuOpen(true)}
                        onMouseLeave={() => setErrorLogsMenuOpen(false)} />}>
                      <FileText className="size-3" />
                      {t("apps.pipeline.viewLogs")}
                      <ChevronDown className="size-3" />
                    </PopoverTrigger>
                    <PopoverContent
                      align="start"
                      className="w-48 p-1"
                      onMouseEnter={() => setErrorLogsMenuOpen(true)}
                      onMouseLeave={() => setErrorLogsMenuOpen(false)}
                    >
                      <div className="flex flex-col">
                        {crashLoopPods.map((pod) => (
                          <Link
                            key={pod.name}
                            href={`/apps/${namespace}/${name}/pods/${pod.name}/logs?environment=${encodeURIComponent(pipeline.environment)}`}
                            className="cursor-pointer truncate rounded-sm px-2 py-1.5 text-sm text-foreground hover:bg-accent"
                            onClick={() => setErrorLogsMenuOpen(false)}
                          >
                            {pod.name}
                          </Link>
                        ))}
                      </div>
                    </PopoverContent>
                  </Popover>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Header */}
        <div className="flex items-center justify-between border-b pb-4">
          <div className="flex min-w-0 items-center gap-4">
            {/* Each label sticks to its own badge; the row wraps between pairs rather than
                breaking a label across two lines when the badges outgrow the width. */}
            <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm text-muted-foreground">
              {pipeline && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.id")} <Badge variant="outline"><Copyable value={pipelineId} maxLength={Infinity} /></Badge></span>}
              {pipeline && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.envLabel")} <Badge variant="outline">{pipeline.environment}</Badge></span>}
              {pipeline && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.statusLabel")} <Badge variant={getStatusVariant(pipeline.status)}>{t(statusLabel[pipeline.status] ?? pipeline.status)}</Badge></span>}
              {/* With the build log gone, this badge is the only thing on the page saying why. The
                  header is already a dense row of labelled badges, so the source id stays in the
                  tooltip and the badge itself links to it. */}
              {isRollback && (
                pipeline?.rollbackFromPipelineId ? (
                  <Tooltip>
                    <TooltipTrigger render={
                      <Link href={`/apps/${namespace}/${name}/pipelines/${pipeline.rollbackFromPipelineId}`} className="cursor-pointer" />
                    }>
                      <Badge variant="outline" className="gap-1 whitespace-nowrap">
                        <Undo2 className="size-3" />
                        {t("pipelines.col.rollbackTag")}
                      </Badge>
                    </TooltipTrigger>
                    <TooltipContent>
                      {t("pipelines.col.rollbackFrom")}
                      <span className="font-mono">{pipeline.rollbackFromPipelineId}</span>
                    </TooltipContent>
                  </Tooltip>
                ) : (
                  <Badge variant="outline" className="gap-1 whitespace-nowrap">
                    <Undo2 className="size-3" />
                    {t("pipelines.col.rollbackTag")}
                  </Badge>
                )
              )}
              {pipeline && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.createdAt")} <Badge variant="outline">{dayjs(pipeline.createdTime).format('YYYY-MM-DD HH:mm:ss')}</Badge></span>}
              {deployModeLabel && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.deployMode")} <Badge variant="outline">{deployModeLabel}</Badge></span>}
              {pipeline?.operatorName && <span className="flex items-center gap-2 whitespace-nowrap">{t("apps.pipeline.operator")} <Badge variant="outline">{pipeline.operatorName}</Badge></span>}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {pipeline?.status === "BUILD_SUCCEEDED" && (
              <AlertDialog>
                <AlertDialogTrigger render={<Button variant="default" size="sm" />}>
                  <Rocket className="size-4" />
                  {t("apps.pipeline.deployBtn")}
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
            {(pipeline?.status === "RUNNING" || pipeline?.status === "DEPLOYING" || pipeline?.status === "BUILD_SUCCEEDED") && (
              <AlertDialog>
                <AlertDialogTrigger render={<Button variant="destructive" size="sm" disabled={stopping} />}>
                  <Ban className="size-4" />
                  {stopping ? t("pipelines.stopping") : t("pipelines.col.stop")}
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>{t("pipelines.stopConfirmTitle")}</AlertDialogTitle>
                    <AlertDialogDescription>
                      {t("pipelines.stopConfirmDesc")}
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel disabled={stopping}>{t("common.cancel")}</AlertDialogCancel>
                    <AlertDialogAction onClick={handleStop} disabled={stopping}>{t("pipelines.stopConfirm")}</AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            )}
          </div>
        </div>

        {/* Content: logs left, status right. Held back until the pipeline is loaded: whether
            there is a build log to show depends on its trigger type, and rendering the log
            panel on the guess and pulling it away a moment later reads as a glitch. */}
        {pipeline && (
          <div className="flex-1 flex gap-4 overflow-hidden min-h-0">
            {/* Left column: steps + logs — a rollback runs no build job, so it has no log stream */}
            {!isRollback && (
              <div className="flex-1 flex flex-col gap-3 overflow-hidden min-h-0">
                {/* Steps Progress Bar — driven by the build pod's container statuses, not by which
                    log happens to be arriving, so every step reads right even when its log is not loaded. */}
                {visibleSteps.length > 0 && (
                  <div className="flex items-start px-1 pt-1 pb-5">
                    {visibleSteps.map((step, index) => {
                      const isViewed = step.name === viewedStep
                      // Labels hang below a 24px circle and are wider than it. The circles sit on
                      // the panel's edges, so an end label is anchored to its circle's outer edge
                      // instead of centred, or it would run past the panel and be clipped.
                      const labelAlignment =
                        index === 0 ? "left-0 text-left"
                        : index === visibleSteps.length - 1 ? "right-0 text-right"
                        : "left-1/2 -translate-x-1/2 text-center"
                      // A pending step has no log yet; once the build has finished it never will.
                      const notRun = step.state === "PENDING" && buildFinished
                      const clickable = step.state !== "PENDING"
                      const circle =
                        step.state === "FAILED" ? "bg-destructive text-white"
                        : step.state === "PENDING" ? "bg-muted text-muted-foreground"
                        : "bg-primary text-primary-foreground"
                      const detail =
                        step.state === "FAILED" ? [`exit ${step.exitCode ?? "?"}`, step.reason].filter(Boolean).join(" · ")
                        : notRun ? t("apps.pipeline.stepNotRun")
                        : step.state === "PENDING" ? (step.reason ?? "")
                        : stepDuration(step)
                      return (
                        <div key={step.name} className="flex items-center flex-1 last:flex-none">
                          <button
                            type="button"
                            disabled={!clickable}
                            onClick={() => setPinnedStep(step.name)}
                            title={detail || undefined}
                            className="flex flex-col items-center relative w-6 cursor-pointer disabled:cursor-not-allowed"
                          >
                            <div className={`size-6 rounded-full flex items-center justify-center text-xs font-medium shrink-0 ${circle} ${isViewed ? "ring-2 ring-primary ring-offset-2 ring-offset-background" : ""}`}>
                              {step.state === "SUCCEEDED" && <Check className="size-3" />}
                              {step.state === "FAILED" && <X className="size-3" />}
                              {step.state === "RUNNING" && <Loader2 className="size-3 animate-spin" />}
                              {step.state === "PENDING" && (notRun ? "–" : index + 1)}
                            </div>
                            <span className={`absolute top-full mt-1 text-xs truncate max-w-24 whitespace-nowrap ${labelAlignment} ${isViewed ? "text-foreground font-medium" : "text-muted-foreground"}`}>
                              {step.name}
                            </span>
                          </button>
                          {index < visibleSteps.length - 1 && (
                            <div className={`flex-1 h-0.5 mx-1 ${step.state === "SUCCEEDED" ? "bg-primary" : "bg-muted"}`} />
                          )}
                        </div>
                      )
                    })}
                  </div>
                )}
                {/* Logs Area */}
                <div className="flex-1 bg-console text-console-foreground border border-console-border rounded-md p-4 font-mono text-sm overflow-hidden flex flex-col min-h-0">
                  {viewedStep && (
                    <div className="flex min-h-6 items-center justify-between gap-2 pb-2 mb-2 border-b border-console-border font-sans text-xs">
                      <span className="text-console-muted">
                        {t("apps.pipeline.stepLogTitle")} <span className="font-mono text-console-foreground">{viewedStep}</span>
                      </span>
                      {/* Pinning a step must not yank the viewer along when the build moves on; this is
                          the way back to following it. */}
                      {pinnedStep !== null && pinnedStep !== followedStep && (
                        <Button variant="ghost" size="xs" className="h-6 cursor-pointer text-console-muted hover:text-console-foreground" onClick={() => setPinnedStep(null)}>
                          <Radio className="size-3" />
                          {t("apps.pipeline.followLatest")}
                        </Button>
                      )}
                    </div>
                  )}
                  <div ref={logContainerRef} className="flex-1 min-h-0 overflow-auto whitespace-pre">
                    {logRows.map((row) => {
                      // Only a stamped line gets the time gutter. The unstamped ones are OOPS's own
                      // notices, not container output, so an empty gutter would just indent them away
                      // from the left edge for a column that says nothing.
                      const time = formatLogTime(row.time ?? undefined)
                      return (
                        <div key={row.id}>
                          {time && (
                            <span className="inline-block w-[4.5rem] shrink-0 select-none text-console-muted tabular-nums">
                              {time}
                            </span>
                          )}
                          {row.text}
                        </div>
                      )
                    })}
                    {logError && <div className="text-destructive">[ERROR] {logError}</div>}
                    {stepsError && <div className="text-destructive">[ERROR] {stepsError}</div>}
                    {logRows.length === 0 && !logError && !stepsError && (
                      <div className="text-console-muted">
                        {viewedStep ? t("apps.pipeline.waitingForLogs") : t("apps.pipeline.waitingForBuild")}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}
            {/* Application Status (right) */}
            <div className="flex-1 border rounded-md p-4 flex flex-col gap-3 overflow-y-auto">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold">{t("apps.pipeline.runningStatus")}</h3>
                {pipeline?.environment && (
                  <Link
                    href={`/apps/${namespace}/${name}/status?environment=${pipeline.environment}`}
                    className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
                  >
                    {t("apps.pipeline.viewDetails")}
                    <ArrowUpRight className="size-3.5" />
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
                    <div className="grid grid-cols-[auto_auto] gap-x-2 gap-y-1 items-center w-fit">
                      {clusterDomain.externalDomains.map((domain, index) => (
                        <Fragment key={domain}>
                          <span className="font-medium whitespace-nowrap">{index === 0 ? t("apps.pipeline.externalDomain") : ""}</span>
                          <span className="flex items-center gap-1.5">
                            <Copyable value={domain} maxLength={Infinity} />
                            <a href={domain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                              <ExternalLink className="size-4" />
                            </a>
                          </span>
                        </Fragment>
                      ))}
                    </div>
                  )}
                </div>
              )}
              <DataTable columns={statusColumns} data={podStatuses} loading={statusLoading} getRowId={(row) => row.name} renderExpandedRow={renderExpandedRow} />
              {pipeline?.environment && (
                <ApplicationEventsPanel
                  namespace={namespace}
                  applicationName={name}
                  environment={pipeline.environment}
                  since={applicationEventSince}
                  limit={100}
                  compact
                />
              )}
            </div>
          </div>
        )}
      </div>
    </ContentPage>
  )
}
