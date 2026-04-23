"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Trash2 } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Domain } from "@/lib/api/domains"

interface TableMeta {
  onEdit: (domain: Domain) => void
  onDelete: (domain: Domain) => void
  isAdmin: boolean
}

export const getColumns = (t: (key: string) => string): ColumnDef<Domain>[] => [
  {
    accessorKey: "host",
    header: t("domains.col.host"),
    cell: ({ row }) => <Copyable value={row.original.host} maxLength={40} />,
  },
  {
    accessorKey: "description",
    header: t("common.description"),
    cell: ({ row }) => row.original.description
      ? <span>{row.original.description}</span>
      : <span className="text-muted-foreground">-</span>,
  },
  {
    accessorKey: "https",
    header: "HTTPS",
    cell: ({ row }) => row.original.https
      ? <Badge variant="default">ON</Badge>
      : <Badge variant="secondary">OFF</Badge>,
  },
  {
    accessorKey: "certMode",
    header: t("domains.col.certMode"),
    cell: ({ row }) => {
      const d = row.original
      if (!d.https) return <span className="text-muted-foreground">-</span>
      if (d.certMode === "AUTO") return <Badge variant="outline">{t("domains.certMode.auto")}</Badge>
      if (d.certMode === "UPLOADED") return <Badge variant="outline">{t("domains.certMode.uploaded")}</Badge>
      return <span className="text-muted-foreground">-</span>
    },
  },
  {
    accessorKey: "certNotAfter",
    header: t("domains.col.certNotAfter"),
    cell: ({ row }) => {
      const d = row.original
      if (d.certMode !== "UPLOADED" || !d.certNotAfter) return <span className="text-muted-foreground">-</span>
      return <span>{new Date(d.certNotAfter).toLocaleString()}</span>
    },
  },
  {
    accessorKey: "createdTime",
    header: t("domains.col.createdTime"),
    cell: ({ row }) => (
      <span>
        {row.original.createdTime
          ? new Date(row.original.createdTime).toLocaleString()
          : "-"}
      </span>
    ),
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const domain = row.original
      const meta = table.options.meta as TableMeta
      if (!meta?.isAdmin) return null
      return (
        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" size="sm" onClick={() => meta?.onEdit(domain)}>
            <Pencil className="h-4 w-4" />
            {t("common.edit")}
          </Button>
          <Button variant="destructive" size="sm" onClick={() => meta?.onDelete(domain)}>
            <Trash2 className="h-4 w-4" />
            {t("common.delete")}
          </Button>
        </div>
      )
    },
  },
]
