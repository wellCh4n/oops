"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pipeline } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Eye, Ban } from "lucide-react"

export const getPipelineColumns = (
  onView: (pipeline: Pipeline) => void,
  onStop: (pipeline: Pipeline) => void
): ColumnDef<Pipeline>[] => [
  {
    accessorKey: "id",
    header: "ID",
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.id}</span>
  },
  {
    accessorKey: "environment",
    header: "环境",
  },
  {
    accessorKey: "status",
    header: "状态",
    cell: ({ row }) => {
      const status = row.original.status
      let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
      if (status === "RUNNING") variant = "default"
      if (status === "SUCCEEDED") variant = "secondary"
      if (status === "ERROR" || status === "STOPED") variant = "destructive"
      
      return <Badge variant={variant}>{status}</Badge>
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
          {(row.original.status === "RUNNING" || row.original.status === "PENDING") && (
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
