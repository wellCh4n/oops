"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Area, AreaChart, CartesianGrid, ReferenceLine, XAxis, YAxis } from "recharts"
import { Loader2, RefreshCw, X } from "lucide-react"

import {
  getApplicationMetricsHistory,
  getApplicationRuntimeSpec,
  type MetricAggregation,
  type MetricHistoryRange,
} from "@/lib/api/applications"
import type { ApplicationRuntimeSpecEnvironmentConfig, PodMetricHistory } from "@/lib/api/types"
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Button } from "@/components/ui/button"
import { useLanguage } from "@/contexts/language-context"
import { isAdmin } from "@/lib/auth"

const RANGES: MetricHistoryRange[] = ["30m", "1h", "6h", "24h"]

/** Error code the backend returns when the cluster has no Prometheus-compatible backend to query. */
const NOT_AVAILABLE = "MONITORING_NOT_AVAILABLE"

/** Matches the sampling interval, so the chart gains a point roughly as soon as one exists. */
const REFRESH_INTERVAL_MS = 30_000

const CHART_COLORS = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
]

const REQUEST_LINE_COLOR = "var(--muted-foreground)"
const LIMIT_LINE_COLOR = "var(--destructive)"

interface ApplicationMetricsDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  namespace: string
  applicationName: string
  environmentName: string
}

/**
 * One row per timestamp with a column per pod, which is the shape recharts wants: pods that were not running at a
 * given timestamp simply have no key on that row, and recharts leaves a gap in their line rather than dropping to
 * zero.
 */
type ChartRow = { timestamp: number } & Record<string, number>

function buildRows(history: PodMetricHistory, metric: "cpuMillis" | "memoryBytes"): ChartRow[] {
  const rowsByTimestamp = new Map<number, ChartRow>()
  for (const series of history.series) {
    for (const point of series.points) {
      let row = rowsByTimestamp.get(point.timestamp)
      if (!row) {
        row = { timestamp: point.timestamp } as ChartRow
        rowsByTimestamp.set(point.timestamp, row)
      }
      row[series.podName] = point[metric]
    }
  }
  return Array.from(rowsByTimestamp.values()).sort((a, b) => a.timestamp - b.timestamp)
}

/**
 * Request/limit levels for one metric, already in the unit the chart plots (millicores, bytes). Undefined means the
 * runtime spec leaves that side unset, so no line is drawn.
 */
interface ResourceGuides {
  request?: number
  limit?: number
}

/**
 * The runtime spec stores whatever the user typed into a "core"-suffixed input, which K8s accepts either as a bare
 * core count ("0.5") or a millicore quantity ("500m").
 */
function parseCpuMillis(value: string | undefined): number | undefined {
  const text = value?.trim()
  if (!text) return undefined
  const millis = text.endsWith("m") ? Number(text.slice(0, -1)) : Number(text) * 1000
  return Number.isFinite(millis) && millis > 0 ? millis : undefined
}

/** Memory is stored as a bare MiB count — StatefulSetProcessor appends the "Mi" unit when building the container. */
function parseMemoryBytes(value: string | undefined): number | undefined {
  const text = value?.trim().replace(/Mi$/i, "")
  if (!text) return undefined
  const bytes = Number(text) * 1024 * 1024
  return Number.isFinite(bytes) && bytes > 0 ? bytes : undefined
}

function formatCores(cpuMillis: number): string {
  return (cpuMillis / 1000).toFixed(cpuMillis < 100 ? 3 : 2)
}

function formatMebibytes(memoryBytes: number): string {
  return (memoryBytes / 1024 / 1024).toFixed(0)
}

function formatClock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
}

export function ApplicationMetricsDrawer({
  open,
  onOpenChange,
  namespace,
  applicationName,
  environmentName,
}: ApplicationMetricsDrawerProps) {
  const { t } = useLanguage()
  const [range, setRange] = useState<MetricHistoryRange>("1h")
  const [aggregation, setAggregation] = useState<MetricAggregation>("avg")
  const [history, setHistory] = useState<PodMetricHistory | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [environmentSpec, setEnvironmentSpec] = useState<
    ApplicationRuntimeSpecEnvironmentConfig | null
  >(null)

  // Only the first load of a given range should blank the charts; the periodic refresh swaps the data in place so
  // the drawer does not flash a spinner every 30 seconds.
  const hasLoadedRef = useRef(false)

  const load = useCallback(
    async (showSpinner: boolean) => {
      if (!environmentName) return
      if (showSpinner) setLoading(true)
      try {
        const response = await getApplicationMetricsHistory(
          namespace,
          applicationName,
          environmentName,
          range,
          aggregation
        )
        if (response.success && response.data) {
          setHistory(response.data)
          setError(null)
        } else {
          setError(response.message || t("apps.metrics.fetchError"))
        }
      } catch {
        setError(t("apps.metrics.fetchError"))
      } finally {
        setLoading(false)
        hasLoadedRef.current = true
      }
    },
    [namespace, applicationName, environmentName, range, aggregation, t]
  )

  useEffect(() => {
    if (!open) {
      hasLoadedRef.current = false
      return
    }
    load(!hasLoadedRef.current)
    const timer = setInterval(() => load(false), REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [open, load])

  // Reset to a first-load spinner whenever the query changes, since the previous range's data is not comparable.
  useEffect(() => {
    hasLoadedRef.current = false
  }, [range, aggregation, environmentName])

  // Configured request/limit levels, drawn as guide lines. They only change when someone edits the runtime spec, so
  // this is fetched once per open rather than on the refresh tick.
  useEffect(() => {
    if (!open || !environmentName) return
    let cancelled = false
    getApplicationRuntimeSpec(namespace, applicationName)
      .then((response) => {
        if (cancelled) return
        const config = response.data?.environmentConfigs?.find(
          (candidate) => candidate.environmentName === environmentName
        )
        setEnvironmentSpec(config ?? null)
      })
      .catch(() => {
        // Guide lines are supplementary; a missing runtime spec must not blank the charts.
        if (!cancelled) setEnvironmentSpec(null)
      })
    return () => {
      cancelled = true
    }
  }, [open, namespace, applicationName, environmentName])

  const cpuGuides = useMemo<ResourceGuides>(
    () => ({
      request: parseCpuMillis(environmentSpec?.cpuRequest),
      limit: parseCpuMillis(environmentSpec?.cpuLimit),
    }),
    [environmentSpec]
  )

  const memoryGuides = useMemo<ResourceGuides>(
    () => ({
      request: parseMemoryBytes(environmentSpec?.memoryRequest),
      limit: parseMemoryBytes(environmentSpec?.memoryLimit),
    }),
    [environmentSpec]
  )

  const podNames = useMemo(() => history?.series.map((series) => series.podName) ?? [], [history])

  const chartConfig = useMemo<ChartConfig>(() => {
    return Object.fromEntries(
      podNames.map((podName, index) => [
        podName,
        { label: podName, color: CHART_COLORS[index % CHART_COLORS.length] },
      ])
    )
  }, [podNames])

  const cpuRows = useMemo(() => (history ? buildRows(history, "cpuMillis") : []), [history])
  const memoryRows = useMemo(() => (history ? buildRows(history, "memoryBytes") : []), [history])

  const renderGuideLegend = (guides: ResourceGuides, formatValue: (value: number) => string) => {
    const entries: { color: string; label: string; value: number }[] = []
    if (guides.request !== undefined) {
      entries.push({
        color: REQUEST_LINE_COLOR,
        label: t("apps.metrics.request"),
        value: guides.request,
      })
    }
    if (guides.limit !== undefined) {
      entries.push({ color: LIMIT_LINE_COLOR, label: t("apps.metrics.limit"), value: guides.limit })
    }
    if (entries.length === 0) return null
    return (
      <div className="flex items-center gap-3 text-xs text-muted-foreground">
        {entries.map((entry) => (
          <span key={entry.label} className="flex items-center gap-1.5">
            <span
              className="h-0 w-4 border-t border-dashed"
              style={{ borderColor: entry.color }}
              aria-hidden
            />
            {entry.label}
            <span className="font-mono tabular-nums">{formatValue(entry.value)}</span>
          </span>
        ))}
      </div>
    )
  }

  const renderChart = (
    rows: ChartRow[],
    formatValue: (value: number) => string,
    axisWidth: number,
    guides: ResourceGuides
  ) => {
    if (loading && rows.length === 0) {
      return (
        <div className="flex h-64 items-center justify-center text-muted-foreground">
          <Loader2 className="size-5 animate-spin" />
        </div>
      )
    }
    if (rows.length === 0) {
      if (error === NOT_AVAILABLE) {
        return (
          <div className="flex h-64 flex-col items-center justify-center gap-2 px-6 text-center text-sm text-muted-foreground">
            <span>{t("apps.metrics.notAvailable")}</span>
            {isAdmin() && <span className="text-xs">{t("apps.metrics.notAvailableAdmin")}</span>}
          </div>
        )
      }
      return (
        <div className="flex h-64 items-center justify-center px-6 text-center text-sm text-muted-foreground">
          {error ?? t("apps.metrics.empty")}
        </div>
      )
    }
    return (
      <ChartContainer config={chartConfig} className="h-64 w-full">
        <AreaChart data={rows} margin={{ left: 4, right: 12, top: 8 }}>
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="timestamp"
            type="number"
            domain={["dataMin", "dataMax"]}
            scale="time"
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            minTickGap={32}
            tickFormatter={formatClock}
          />
          <YAxis
            width={axisWidth}
            tickLine={false}
            axisLine={false}
            tickMargin={4}
            tickFormatter={formatValue}
          />
          <ChartTooltip
            content={
              <ChartTooltipContent
                labelFormatter={(_, items) => {
                  // The tooltip label defaults to the series config label, not the X value, so read the
                  // timestamp back off the hovered row.
                  const row = (items?.[0] as { payload?: ChartRow } | undefined)?.payload
                  return row ? formatClock(row.timestamp) : ""
                }}
                formatter={(value, name) => (
                  <>
                    <div
                      className="size-2.5 shrink-0 rounded-[2px]"
                      style={{ backgroundColor: chartConfig[name as string]?.color }}
                    />
                    <span className="text-muted-foreground">{name}</span>
                    <span className="ml-auto font-mono font-medium tabular-nums text-foreground">
                      {formatValue(Number(value))}
                    </span>
                  </>
                )}
              />
            }
          />
          <ChartLegend content={<ChartLegendContent />} />
          {podNames.map((podName) => (
            <Area
              key={podName}
              dataKey={podName}
              type="monotone"
              stroke={chartConfig[podName]?.color}
              fill={chartConfig[podName]?.color}
              fillOpacity={0.15}
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
              connectNulls
            />
          ))}
          {/* Drawn after the areas so the guides stay on top; extendDomain grows the Y axis to keep them visible, which
              is the point of the lines — the headroom between usage and limit should be readable at a glance. */}
          {guides.request !== undefined && (
            <ReferenceLine
              y={guides.request}
              ifOverflow="extendDomain"
              stroke={REQUEST_LINE_COLOR}
              strokeDasharray="4 4"
            />
          )}
          {guides.limit !== undefined && (
            <ReferenceLine
              y={guides.limit}
              ifOverflow="extendDomain"
              stroke={LIMIT_LINE_COLOR}
              strokeDasharray="4 4"
            />
          )}
        </AreaChart>
      </ChartContainer>
    )
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange} swipeDirection="right">
      <DrawerContent className="sm:[--drawer-content-width:min(56rem,92vw)]">
        <DrawerHeader className="border-b pb-4 md:text-left">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <DrawerTitle>{t("apps.metrics.title")}</DrawerTitle>
              <DrawerDescription className="truncate">
                {applicationName} · {environmentName}
              </DrawerDescription>
            </div>
            {/* Clicking the scrim closes the drawer too, but nothing on screen
                says so — Dialog puts an explicit X in the same corner, so this
                one does as well. */}
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => load(true)}
                disabled={loading}
                aria-label={t("apps.metrics.refresh")}
              >
                <RefreshCw className={loading ? "animate-spin" : undefined} />
              </Button>
              <DrawerClose
                render={<Button variant="ghost" size="icon-sm" aria-label={t("common.close")} />}
              >
                <X />
              </DrawerClose>
            </div>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-3">
            <Tabs value={range} onValueChange={(value) => setRange(value as MetricHistoryRange)}>
              <TabsList>
                {RANGES.map((option) => (
                  <TabsTrigger key={option} value={option} className="px-3">
                    {t(`apps.metrics.range.${option}`)}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>

            <Tabs
              value={aggregation}
              onValueChange={(value) => setAggregation(value as MetricAggregation)}
            >
              <TabsList variant="line">
                <TabsTrigger value="avg" className="px-3">
                  {t("apps.metrics.agg.avg")}
                </TabsTrigger>
                <TabsTrigger value="max" className="px-3">
                  {t("apps.metrics.agg.max")}
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        </DrawerHeader>

        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          <div className="flex flex-col gap-6">
            <section className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-medium">{t("apps.metrics.cpuTitle")}</h3>
                {renderGuideLegend(cpuGuides, formatCores)}
              </div>
              {renderChart(cpuRows, formatCores, 48, cpuGuides)}
            </section>
            <section className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-medium">{t("apps.metrics.memoryTitle")}</h3>
                {renderGuideLegend(memoryGuides, formatMebibytes)}
              </div>
              {renderChart(memoryRows, formatMebibytes, 44, memoryGuides)}
            </section>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
