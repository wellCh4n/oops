"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Namespace } from "@/lib/api/types"

interface TableMeta {
  onEdit: (namespace: Namespace) => void
}

export const getColumns = (t: (key: string) => string): ColumnDef<Namespace>[] => [
  {
    accessorKey: "name",
    header: t("ns.col.name"),
  },
  {
    accessorKey: "description",
    header: t("common.description"),
    cell: ({ row }) => row.original.description || "-",
  },
  {
    id: "actions",
    header: () => <div className="text-right"></div>,
    cell: ({ row, table }) => {
      const namespace = row.original
      const meta = table.options.meta as TableMeta

      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onEdit(namespace)}
            title={t("common.edit")}
          >
            <Pencil className="h-4 w-4" />
            {t("common.edit")}
          </Button>
        </div>
      )
    },
  },
]
