"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Rocket, Activity } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Application } from "@/lib/api/types"

// Define the shape of our table meta to include handlers
interface TableMeta {
  onEdit: (application: Application) => void
  onPublish: (application: Application) => void
  onStatus: (application: Application) => void
}

export const columns: ColumnDef<Application>[] = [
  {
    accessorKey: "name",
    header: "名称",
  },
  {
    accessorKey: "namespace",
    header: "命名空间",
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const application = row.original
      const meta = table.options.meta as TableMeta

      return (
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onEdit(application)}
            title="编辑"
          >
            <Pencil className="mr-2 h-4 w-4" />
            编辑
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onPublish(application)}
            title="发布"
          >
            <Rocket className="mr-2 h-4 w-4" />
            发布
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onStatus(application)}
            title="状态"
          >
            <Activity className="mr-2 h-4 w-4" />
            状态
          </Button>
        </div>
      )
    },
  },
]
