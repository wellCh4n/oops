"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Trash2, Plug } from "lucide-react"
import { Button } from "@/components/ui/button"
import { z } from "zod"
import { Environment } from "@/lib/api/types"

// Define Schema and Types here to avoid circular dependencies or multiple files
export const environmentSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1, "Name is required"),
  apiServerUrl: z.string().url("Must be a valid URL"),
  apiServerToken: z.string().min(1, "Token is required"),
  workNamespace: z.string().min(1, "Namespace is required"),
  imageRepositoryUrl: z.string().url("Must be a valid URL"),
  buildStorageClass: z.string().min(1, "Storage class is required"),
})

export type EnvironmentFormValues = z.infer<typeof environmentSchema>

// Define the shape of our table meta to include handlers
interface TableMeta {
  onEdit: (environment: Environment) => void
  onDelete: (id: string) => void
  onTest: (id: string) => void
}

export const columns: ColumnDef<Environment>[] = [
  {
    accessorKey: "name",
    header: "名称",
  },
  {
    accessorKey: "apiServerUrl",
    header: "API Server URL",
  },
  {
    accessorKey: "workNamespace",
    header: "工作命名空间",
  },
  {
    accessorKey: "imageRepositoryUrl",
    header: "镜像仓库",
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const environment = row.original
      const meta = table.options.meta as TableMeta

      return (
        <div className="flex items-center gap-2">
          <Button 
            variant="ghost" 
            size="icon" 
            onClick={() => meta?.onTest(environment.id)}
            title="测试连接"
          >
            <Plug className="h-4 w-4" />
          </Button>
          <Button 
            variant="ghost" 
            size="icon" 
            onClick={() => meta?.onEdit(environment)}
            title="编辑"
          >
            <Pencil className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="text-red-600 hover:text-red-600 hover:bg-red-50"
            onClick={() => meta?.onDelete(environment.id)}
            title="删除"
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      )
    },
  },
]
