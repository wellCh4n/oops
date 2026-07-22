"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Badge } from "@/components/ui/badge"
import { NodeStatus } from "@/lib/api/types"

export const getColumns = (t: (key: string) => string): ColumnDef<NodeStatus>[] => [
  {
    accessorKey: "name",
    header: t("nodes.col.name"),
    cell: ({ row }) => <span className="font-medium">{row.original.name}</span>,
  },
  {
    id: "status",
    header: t("nodes.col.status"),
    cell: ({ row }) => (
      <Badge variant={row.original.ready ? "default" : "destructive"}>
        {row.original.ready ? t("nodes.status.ready") : t("nodes.status.notReady")}
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
    cell: ({ row }) => row.original.cpu || "-",
  },
  {
    accessorKey: "memory",
    header: t("nodes.col.memory"),
    cell: ({ row }) => row.original.memory || "-",
  },
  {
    accessorKey: "pods",
    header: t("nodes.col.pods"),
    cell: ({ row }) => row.original.pods || "-",
  },
  {
    accessorKey: "kubeletVersion",
    header: t("nodes.col.version"),
    cell: ({ row }) => row.original.kubeletVersion || "-",
  },
]
