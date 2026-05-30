"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { Pipeline } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Eye, Ban, Rocket, Undo2, CircleDot } from "lucide-react"

export const getPipelineColumns = (
  t: (key: string) => string,
  onStop: (pipeline: Pipeline) => void,
  onDeploy: (pipeline: Pipeline) => void,
  onRollback: (pipeline: Pipeline) => void,
  currentPipelineId?: string | null
): ColumnDef<Pipeline>[] => [
  {
    accessorKey: "id",
    header: "ID",
    size: 300,
    cell: ({ row }) => {
      const isCurrent = !!currentPipelineId && row.original.id === currentPipelineId
      return (
        <div className="flex items-center gap-1.5 whitespace-nowrap">
          <Copyable value={row.original.id} maxLength={Infinity} />
          {isCurrent && (
            <Badge variant="default" className="gap-1">
              <CircleDot className="size-3" />
              {t("pipelines.col.currentVersion")}
            </Badge>
          )}
        </div>
      )
    },
  },
  {
    accessorKey: "environment",
    header: t("pipelines.col.environment"),
    size: 80,
  },
  {
    accessorKey: "deployMode",
    header: t("pipelines.col.deployMode"),
    size: 100,
    cell: ({ row }) => {
      const deployMode = row.original.deployMode
      if (!deployMode) return <span className="text-muted-foreground">-</span>
      return deployMode === "IMMEDIATE" ? t("apps.pipeline.modeImmediate") : t("apps.pipeline.modeManual")
    }
  },
  {
    accessorKey: "triggerType",
    header: t("pipelines.col.triggerType"),
    size: 90,
    cell: ({ row }) => {
      const isRollback = row.original.triggerType === "ROLLBACK"
      return (
        <Badge variant={isRollback ? "outline" : "secondary"} className="gap-1">
          {isRollback ? <Undo2 className="size-3" /> : <Rocket className="size-3" />}
          {isRollback ? t("pipelines.col.rollbackTag") : t("pipelines.col.buildTag")}
        </Badge>
      )
    }
  },
  {
    accessorKey: "status",
    header: t("pipelines.col.status"),
    size: 90,
    cell: ({ row }) => {
      const status = row.original.status
      let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
      if (status === "RUNNING" || status === "DEPLOYING") variant = "default"
      if (status === "SUCCEEDED") variant = "secondary"
      if (status === "ERROR" || status === "STOPPED") variant = "destructive"

      const statusKeyMap: Record<string, string> = {
        BUILD_SUCCEEDED: "apps.pipeline.status.BUILD_SUCCEEDED",
        INITIALIZED: "apps.pipeline.status.INITIALIZED",
        RUNNING: "apps.pipeline.status.RUNNING",
        DEPLOYING: "apps.pipeline.status.DEPLOYING",
        SUCCEEDED: "apps.pipeline.status.SUCCEEDED",
        ERROR: "apps.pipeline.status.ERROR",
        STOPPED: "apps.pipeline.status.STOPPED",
      }

      return <Badge variant={variant}>{t(statusKeyMap[status] ?? status)}</Badge>
    }
  },
  {
    accessorKey: "operatorName",
    header: t("pipelines.col.operator"),
    size: 90,
    cell: ({ row }) => {
      return row.original.operatorName || <span className="text-muted-foreground">-</span>
    }
  },
  {
    accessorKey: "createdTime",
    header: t("pipelines.col.createdTime"),
    size: 150,
    cell: ({ row }) => {
        if (!row.original.createdTime) return "-"
        const d = new Date(row.original.createdTime)
        return d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    }
  },
  {
    id: "actions",
    size: 220,
    cell: ({ row }) => {
      return (
        <div className="flex items-center justify-end gap-1.5">
          <Button asChild variant="outline" size="sm" className="h-8 px-2 gap-1">
            <Link href={`/apps/${row.original.namespace}/${row.original.applicationName}/pipelines/${row.original.id}`}>
              <Eye className="size-4" />
              {t("pipelines.col.view")}
            </Link>
          </Button>
          {row.original.status === "BUILD_SUCCEEDED" && (
            <Button variant="default" size="sm" className="h-8 px-2 gap-1" onClick={() => onDeploy(row.original)}>
              <Rocket className="size-4" />
              {t("pipelines.col.deployBtn")}
            </Button>
          )}
          {(row.original.status === "RUNNING" || row.original.status === "DEPLOYING" || row.original.status === "BUILD_SUCCEEDED") && (
            <Button variant="destructive" size="sm" className="h-8 px-2 gap-1" onClick={() => onStop(row.original)}>
              <Ban className="size-4" />
              {t("pipelines.col.stop")}
            </Button>
          )}
          {row.original.status === "SUCCEEDED"
            && !!row.original.artifact
            && !(currentPipelineId && row.original.id === currentPipelineId) && (
            <Button variant="outline" size="sm" className="h-8 px-2 gap-1" onClick={() => onRollback(row.original)}>
              <Undo2 className="size-4" />
              {t("pipelines.col.rollbackBtn")}
            </Button>
          )}
        </div>
      )
    }
  }
]
