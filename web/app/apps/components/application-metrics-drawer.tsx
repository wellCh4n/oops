"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts"
import { Loader2, RefreshCw } from "lucide-react"

import {
  getApplicationMetricsHistory,
  type MetricAggregation,
  type MetricHistoryRange,
} from "@/lib/api/applications"
import type { PodMetricHistory } from "@/lib/api/types"
import {
  Drawer,
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

const RANGES: MetricHistoryRange[] = ["30m", "1h", "6h", "24h"]

/** Matches the sampling interval, so the chart gains a point roughly as soon as one exists. */
const REFRESH_INTERVAL_MS = 30_000

const CHART_COLORS = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
]

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

  const renderChart = (
    rows: ChartRow[],
    formatValue: (value: number) => string,
    axisWidth: number
  ) => {
    if (loading && rows.length === 0) {
      return (
        <div className="flex h-64 items-center justify-center text-muted-foreground">
          <Loader2 className="size-5 animate-spin" />
        </div>
      )
    }
    if (rows.length === 0) {
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
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => load(true)}
              disabled={loading}
              aria-label={t("apps.metrics.refresh")}
            >
              <RefreshCw className={loading ? "animate-spin" : undefined} />
            </Button>
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
              <h3 className="text-sm font-medium">{t("apps.metrics.cpuTitle")}</h3>
              {renderChart(cpuRows, formatCores, 48)}
            </section>
            <section className="flex flex-col gap-2">
              <h3 className="text-sm font-medium">{t("apps.metrics.memoryTitle")}</h3>
              {renderChart(memoryRows, formatMebibytes, 44)}
            </section>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
