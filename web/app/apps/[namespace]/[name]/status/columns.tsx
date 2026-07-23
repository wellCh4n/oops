"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus, PodMetric } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RotateCw, Terminal, FileText, RefreshCw } from "lucide-react"

interface PodLinkContext {
  namespace: string
  applicationName: string
  env: string
}

function formatCpu(cpuMillis: number): string {
  if (cpuMillis >= 1000) {
    return `${(cpuMillis / 1000).toFixed(2)} cores`
  }
  return `${cpuMillis}m`
}

function formatMemory(memoryBytes: number): string {
  const mib = memoryBytes / (1024 * 1024)
  if (mib >= 1024) {
    return `${(mib / 1024).toFixed(2)} GiB`
  }
  return `${Math.round(mib)} MiB`
}

export const getStatusColumns = (
  t: (key: string) => string,
  onRestart: (podName: string) => void,
  linkContext: PodLinkContext,
  metricsByPod: Record<string, PodMetric>,
  onRefreshMetrics: () => void,
  metricsLoading: boolean
): ColumnDef<ApplicationPodStatus>[] => {
  const metricHeader = (label: string) => (
    <div className="flex items-center gap-1">
      {label}
      <button
        type="button"
        onClick={onRefreshMetrics}
        disabled={metricsLoading}
        title={t("apps.status.metricsRefresh")}
        className="cursor-pointer text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed"
      >
        <RefreshCw className={`size-3.5 ${metricsLoading ? "animate-spin" : ""}`} />
      </button>
    </div>
  )

  return [
  {
    accessorKey: "name",
    header: "Pod",
    cell: ({ row }) => {
      const name = row.getValue("name") as string
      return <div className="max-w-48 truncate">{name}</div>
    },
  },
  {
    accessorKey: "status",
    header: t("apps.status.col.status"),
    cell: ({ row }) => {
      const status = row.getValue("status") as string
      return (
        <Badge variant={status === "Running" ? "info" : "destructive"}>
          {status}
        </Badge>
      )
    },
  },
  {
    accessorKey: "podIP",
    header: t("apps.status.col.ip"),
  },
  {
    accessorKey: "nodeName",
    header: t("apps.status.col.node"),
  },
  {
    id: "cpu",
    header: () => metricHeader(t("apps.status.col.cpu")),
    cell: ({ row }) => {
      const metric = metricsByPod[row.original.name]
      return <span className="tabular-nums">{metric ? formatCpu(metric.cpuMillis) : "-"}</span>
    },
  },
  {
    id: "memory",
    header: () => metricHeader(t("apps.status.col.memory")),
    cell: ({ row }) => {
      const metric = metricsByPod[row.original.name]
      return <span className="tabular-nums">{metric ? formatMemory(metric.memoryBytes) : "-"}</span>
    },
  },
  {
    id: "actions",
    cell: ({ row }) => {
      const podName = row.original.name
      const podPath = `/apps/${linkContext.namespace}/${linkContext.applicationName}/pods/${podName}`
      const query = `?env=${encodeURIComponent(linkContext.env)}`
      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant={row.original.status === "Running" ? "warning" : "outline"}
            size="sm"
            onClick={() => onRestart(podName)}
          >
            <RotateCw className="size-4" />
            <span className="hidden lg:inline">{t("apps.status.col.restart")}</span>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${podPath}/logs${query}`}>
              <FileText className="size-4" />
              <span className="hidden lg:inline">{t("apps.status.col.logs")}</span>
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${podPath}/terminal${query}`}>
              <Terminal className="size-4" />
              <span className="hidden lg:inline">{t("apps.status.col.terminal")}</span>
            </Link>
          </Button>
        </div>
      )
    },
  },
  ]
}
