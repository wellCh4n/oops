"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { Pipeline } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { AppIdentityMark } from "@/components/app-identity-mark"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import { Eye, Ban, Rocket, Undo2, CircleDot, LayoutGrid } from "lucide-react"

export const getPipelineColumns = (
  t: (key: string) => string,
  onStop: (pipeline: Pipeline) => void,
  onDeploy: (pipeline: Pipeline) => void,
  onRollback: (pipeline: Pipeline) => void,
  currentPipelineId?: string | null,
  // False under the "all environments" scope: the list then mixes environments, the live image of
  // each is unknown, and rolling back without knowing which row is the running one is a trap.
  rollbackEnabled: boolean = true,
  // True when the list spans applications, so each row has to say which one it belongs to.
  showApplication: boolean = false
): ColumnDef<Pipeline>[] => [
  {
    accessorKey: "id",
    header: "ID",
    size: 240,
    cell: ({ row }) => {
      const isCurrent = !!currentPipelineId && row.original.id === currentPipelineId
      return (
        <div className="flex items-center gap-1.5 whitespace-nowrap">
          <Copyable value={row.original.id} maxLength={Infinity} />
          {isCurrent && (
            <Badge variant="info" className="gap-1">
              <CircleDot className="size-3" />
              {t("pipelines.col.currentVersion")}
            </Badge>
          )}
        </div>
      )
    },
  },
  ...(showApplication ? [{
    id: "application",
    header: t("pipelines.col.application"),
    // Copyable like the id, with the namespace on hover; the link to the app is in the actions.
    cell: ({ row }) => (
      <Tooltip>
        <TooltipTrigger render={<span className="inline-flex items-center gap-2" />}>
          <AppIdentityMark seed={{ namespace: row.original.namespace, name: row.original.applicationName, icon: row.original.applicationIcon }} />
          <Copyable value={row.original.applicationName} maxLength={Infinity} className="font-sans" />
        </TooltipTrigger>
        <TooltipContent>{t("common.namespace")}: {row.original.namespace}</TooltipContent>
      </Tooltip>
    ),
  } satisfies ColumnDef<Pipeline>] : []),
  {
    accessorKey: "environment",
    header: t("pipelines.col.environment"),
    size: 110,
  },
  {
    accessorKey: "deployMode",
    header: t("pipelines.col.deployMode"),
    size: 100,
    // The trigger type rides along as a mark: a rollback row keeps its deploy mode and
    // gets a rollback icon whose tooltip names the pipeline it was rolled back from.
    cell: ({ row }) => {
      const deployMode = row.original.deployMode
      const modeLabel = !deployMode
        ? <span className="text-muted-foreground">-</span>
        : deployMode === "IMMEDIATE" ? t("apps.pipeline.modeImmediate") : t("apps.pipeline.modeManual")
      const isRollback = row.original.triggerType === "ROLLBACK"
      const fromId = row.original.rollbackFromPipelineId
      return (
        <span className="inline-flex items-center gap-1 whitespace-nowrap">
          {modeLabel}
          {isRollback && (
            <Tooltip>
              <TooltipTrigger render={<Undo2 className="size-3.5 text-muted-foreground cursor-help" />}></TooltipTrigger>
              <TooltipContent>
                {t("pipelines.col.rollbackTag")}
                {fromId && <> · {t("pipelines.col.rollbackFrom")}{fromId}</>}
              </TooltipContent>
            </Tooltip>
          )}
        </span>
      )
    }
  },
  {
    accessorKey: "status",
    header: t("pipelines.col.status"),
    size: 80,
    cell: ({ row }) => {
      const status = row.original.status
      let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
      if (status === "RUNNING" || status === "DEPLOYING" || status === "ROLLING_OUT") variant = "default"
      if (status === "SUCCEEDED") variant = "secondary"
      if (status === "ERROR" || status === "STOPPED") variant = "destructive"

      const statusKeyMap: Record<string, string> = {
        BUILD_SUCCEEDED: "apps.pipeline.status.BUILD_SUCCEEDED",
        INITIALIZED: "apps.pipeline.status.INITIALIZED",
        RUNNING: "apps.pipeline.status.RUNNING",
        DEPLOYING: "apps.pipeline.status.DEPLOYING",
        ROLLING_OUT: "apps.pipeline.status.ROLLING_OUT",
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
    size: 140,
    cell: ({ row }) => {
        if (!row.original.createdTime) return "-"
        const d = new Date(row.original.createdTime)
        return d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    }
  },
  {
    id: "actions",
    // No fixed width: the button set varies per row, and a width narrower than the
    // widest set clipped it. The cell is nowrap, so it takes exactly what it needs.
    cell: ({ row }) => {
      return (
        <div className="flex items-center justify-end gap-1.5 whitespace-nowrap">
          <Button render={<Link href={`/apps/${row.original.namespace}/${row.original.applicationName}/pipelines/${row.original.id}`} />} variant="outline" size="sm" className="h-8 px-2 gap-1">
            <Eye className="size-4" />
            {t("pipelines.col.view")}
          </Button>
          <Button render={<Link href={`/apps/${row.original.namespace}/${row.original.applicationName}`} />} variant="outline" size="sm" className="h-8 px-2 gap-1">
            <LayoutGrid className="size-4" />
            {t("pipelines.col.application")}
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
          {/* A rollback copies its source's artifact, so its own row would offer the very same image
              the source row already offers — and while it is the live version the id comparison
              below cannot recognise it (the tag carries the source's id), so the button would sit
              there redeploying what is already running. */}
          {rollbackEnabled
            && row.original.triggerType !== "ROLLBACK"
            && row.original.status === "SUCCEEDED"
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
