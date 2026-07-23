"use client"

import { ColumnDef } from "@tanstack/react-table"
import { PauseCircle, PlayCircle } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { NodeStatus } from "@/lib/api/types"

interface ColumnOptions {
  canManage?: boolean
  onToggleSchedulable?: (node: NodeStatus) => void
}

export const getColumns = (
  t: (key: string) => string,
  options: ColumnOptions = {}
): ColumnDef<NodeStatus>[] => {
  const { canManage = false, onToggleSchedulable } = options

  const columns: ColumnDef<NodeStatus>[] = [
    {
      accessorKey: "name",
      header: t("nodes.col.name"),
      cell: ({ row }) => <span className="font-medium">{row.original.name}</span>,
    },
    {
      id: "status",
      header: t("nodes.col.status"),
      size: 100,
      cell: ({ row }) => (
        <Badge variant={row.original.ready ? "success" : "destructive"}>
          {row.original.ready ? t("nodes.status.ready") : t("nodes.status.notReady")}
        </Badge>
      ),
    },
    {
      id: "scheduling",
      header: t("nodes.col.scheduling"),
      size: 100,
      cell: ({ row }) => (
        <Badge variant={row.original.schedulable ? "success" : "warning"}>
          {row.original.schedulable
            ? t("nodes.scheduling.enabled")
            : t("nodes.scheduling.disabled")}
        </Badge>
      ),
    },
    {
      accessorKey: "roles",
      header: t("nodes.col.role"),
      cell: ({ row }) => row.original.roles || "-",
    },
    {
      accessorKey: "internalIP",
      header: t("nodes.col.ip"),
      cell: ({ row }) => row.original.internalIP || "-",
    },
    {
      accessorKey: "cpu",
      header: t("nodes.col.cpu"),
      size: 80,
      cell: ({ row }) => row.original.cpu || "-",
    },
    {
      accessorKey: "memory",
      header: t("nodes.col.memory"),
      size: 120,
      cell: ({ row }) => row.original.memory || "-",
    },
    {
      accessorKey: "kubeletVersion",
      header: t("nodes.col.version"),
      cell: ({ row }) => row.original.kubeletVersion || "-",
    },
  ]

  if (canManage) {
    columns.push({
      id: "actions",
      header: "",
      cell: ({ row }) => (
        <div className="flex justify-end">
          <Button
            variant="outline"
            size="sm"
            className="cursor-pointer"
            onClick={() => onToggleSchedulable?.(row.original)}
          >
            {row.original.schedulable ? (
              <>
                <PauseCircle className="size-4" />
                {t("nodes.action.cordon")}
              </>
            ) : (
              <>
                <PlayCircle className="size-4" />
                {t("nodes.action.uncordon")}
              </>
            )}
          </Button>
        </div>
      ),
    })
  }

  return columns
}
