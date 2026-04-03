"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Rocket, Activity, GitBranch, SquareCode } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Application } from "@/lib/api/types"

// Define the shape of our table meta to include handlers
interface TableMeta {
  onEdit: (application: Application) => void
  onPublish: (application: Application) => void
  onStatus: (application: Application) => void
  onPipelines: (application: Application) => void
  onIDE: (application: Application) => void
  ideEnabled: boolean
}

export const getColumns = (t: (key: string) => string): ColumnDef<Application>[] => [
  {
    accessorKey: "name",
    header: t("apps.col.name"),
    cell: ({ row }) => <Copyable value={row.original.name} maxLength={Infinity} className="font-sans" />,
  },
  {
    accessorKey: "description",
    header: t("apps.col.description"),
  },
  {
    accessorKey: "namespace",
    header: t("apps.col.namespace"),
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const application = row.original
      const meta = table.options.meta as TableMeta

      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onEdit(application)}
            title={t("apps.col.edit")}
          >
            <Pencil className="h-4 w-4" />
            {t("apps.col.edit")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onPublish(application)}
            title={t("apps.col.publish")}
          >
            <Rocket className="h-4 w-4" />
            {t("apps.col.publish")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onStatus(application)}
            title={t("apps.col.status")}
          >
            <Activity className="h-4 w-4" />
            {t("apps.col.status")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onPipelines(application)}
            title={t("apps.col.pipelines")}
          >
            <GitBranch className="h-4 w-4" />
            {t("apps.col.pipelines")}
          </Button>
          {meta?.ideEnabled && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => meta?.onIDE(application)}
              title={t("apps.col.ide")}
            >
              <SquareCode className="h-4 w-4" />
              {t("apps.col.ide")}
            </Button>
          )}
        </div>
      )
    },
  },
]
