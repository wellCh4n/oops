"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Eye } from "lucide-react"
import { Button } from "@/components/ui/button"
import { z } from "zod"
import { Environment } from "@/lib/api/types"

// Define Schema and Types here to avoid circular dependencies or multiple files
export const environmentSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1, "名称不能为空"),
  apiServerUrl: z.string().url("必须是有效的 URL"),
  apiServerToken: z.string().min(1, "令牌不能为空"),
  workNamespace: z.string().min(1, "命名空间不能为空"),
  imageRepositoryUrl: z.string().min(1, "镜像仓库地址不能为空"),
  imageRepositoryUsername: z.string().optional(),
  imageRepositoryPassword: z.string().optional(),
  buildStorageClass: z.string().optional(),
})

export type EnvironmentFormValues = z.infer<typeof environmentSchema>

// Define the shape of our table meta to include handlers
interface TableMeta {
  onView: (environment: Environment) => void
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
        <div className="flex items-center justify-end gap-2">
          <Button 
            variant="outline" 
            size="sm"
            onClick={() => meta?.onView(environment)}
          >
            <Eye className="mr-2 h-4 w-4" />
            查看
          </Button>
        </div>
      )
    },
  },
]
