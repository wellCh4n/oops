"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pipeline } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Eye, Ban, Rocket } from "lucide-react"

export const getPipelineColumns = (
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
    header: "环境",
  },
  {
    accessorKey: "deployMode",
    header: "发布方式",
    cell: ({ row }) => {
      const deployMode = row.original.deployMode
      if (!deployMode) return <span className="text-muted-foreground">-</span>
      return deployMode === "IMMEDIATE" ? "立即发布" : "手动发布"
    }
  },
  {
    accessorKey: "status",
    header: "状态",
    cell: ({ row }) => {
      const status = row.original.status
      let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
      if (status === "RUNNING" || status === "DEPLOYING") variant = "default"
      if (status === "SUCCEEDED") variant = "secondary"
      if (status === "ERROR" || status === "STOPPED") variant = "destructive"

      const statusLabel: Record<string, string> = {
        BUILD_SUCCEEDED: "编译完成",
        INITIALIZED: "初始化",
        RUNNING: "运行中",
        DEPLOYING: "发布中",
        SUCCEEDED: "成功",
        ERROR: "失败",
        STOPPED: "已停止",
      }

      return <Badge variant={variant}>{statusLabel[status] ?? status}</Badge>
    }
  },
  {
    accessorKey: "createdTime",
    header: "创建时间",
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
            <Eye className="mr-2 h-4 w-4" />
            查看
          </Button>
          {row.original.status === "BUILD_SUCCEEDED" && (
            <Button variant="default" size="sm" onClick={() => onDeploy(row.original)}>
              <Rocket className="mr-2 h-4 w-4" />
              应用此发布
            </Button>
          )}
          {(row.original.status === "RUNNING" || row.original.status === "DEPLOYING") && (
            <Button variant="destructive" size="sm" onClick={() => onStop(row.original)}>
              <Ban className="mr-2 h-4 w-4" />
              停止
            </Button>
          )}
        </div>
      )
    }
  }
]
