"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pipeline } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Eye, Ban, Rocket } from "lucide-react"

export const getPipelineColumns = (
  t: (key: string) => string,
  onView: (pipeline: Pipeline) => void,
  onStop: (pipeline: Pipeline) => void,
  onDeploy: (pipeline: Pipeline) => void
): ColumnDef<Pipeline>[] => [
  {
    accessorKey: "id",
    header: "ID",
    size: 300,
    cell: ({ row }) => (
      <div className="whitespace-nowrap">
        <Copyable value={row.original.id} maxLength={Infinity} />
      </div>
    ),
  },
  {
    accessorKey: "environment",
    header: t("pipelines.col.environment"),
  },
  {
    accessorKey: "deployMode",
    header: t("pipelines.col.deployMode"),
    cell: ({ row }) => {
      const deployMode = row.original.deployMode
      if (!deployMode) return <span className="text-muted-foreground">-</span>
      return deployMode === "IMMEDIATE" ? t("apps.pipeline.modeImmediate") : t("apps.pipeline.modeManual")
    }
  },
  {
    accessorKey: "status",
    header: t("pipelines.col.status"),
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
    accessorKey: "createdTime",
    header: t("pipelines.col.createdTime"),
    cell: ({ row }) => {
        if (!row.original.createdTime) return "-"
        return new Date(row.original.createdTime).toLocaleString()
    }
  },
  {
    id: "actions",
    cell: ({ row }) => {
      return (
        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" size="sm" onClick={() => onView(row.original)}>
            <Eye className="h-4 w-4" />
            {t("pipelines.col.view")}
          </Button>
          {row.original.status === "BUILD_SUCCEEDED" && (
            <Button variant="default" size="sm" onClick={() => onDeploy(row.original)}>
              <Rocket className="h-4 w-4" />
              {t("pipelines.col.deployBtn")}
            </Button>
          )}
          {(row.original.status === "RUNNING" || row.original.status === "DEPLOYING") && (
            <Button variant="destructive" size="sm" onClick={() => onStop(row.original)}>
              <Ban className="h-4 w-4" />
              {t("pipelines.col.stop")}
            </Button>
          )}
        </div>
      )
    }
  }
]
