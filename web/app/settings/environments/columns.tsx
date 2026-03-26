"use client"

import { ColumnDef } from "@tanstack/react-table"
import { Pencil } from "lucide-react"
import { Button } from "@/components/ui/button"
import { z } from "zod"
import { Environment } from "@/lib/api/types"

// Define Schema and Types here to avoid circular dependencies or multiple files
export const environmentSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1),
  kubernetesApiServer: z.object({
    url: z.string().optional(),
    token: z.string().optional(),
  }).optional(),
  workNamespace: z.string().optional(),
  imageRepository: z.object({
    url: z.string().optional(),
    username: z.string().optional(),
    password: z.string().optional(),
  }).optional(),
  buildStorageClass: z.string().optional(),
})

export type EnvironmentFormValues = z.infer<typeof environmentSchema>

export const getEnvironmentSchema = (t: (key: string) => string) => z.object({
  id: z.string().optional(),
  name: z.string().min(1, t("env.nameRequired")),
  kubernetesApiServer: z.object({
    url: z.string().optional(),
    token: z.string().optional(),
  }).optional(),
  workNamespace: z.string().optional(),
  imageRepository: z.object({
    url: z.string().optional(),
    username: z.string().optional(),
    password: z.string().optional(),
  }).optional(),
  buildStorageClass: z.string().optional(),
})

// Define the shape of our table meta to include handlers
interface TableMeta {
  onView: (environment: Environment) => void
}

export const getColumns = (t: (key: string) => string): ColumnDef<Environment>[] => [
  {
    accessorKey: "name",
    header: t("env.col.name"),
  },
  {
    accessorKey: "kubernetesApiServer.url",
    header: "API Server URL",
    cell: ({ row }) => (
      <div className="whitespace-normal break-all ">
        {row.original.kubernetesApiServer?.url ?? "-"}
      </div>
    ),
  },
  {
    accessorKey: "workNamespace",
    header: t("env.col.workNamespace"),
    cell: ({ row }) => (
      <div className="whitespace-normal break-all ">
        {row.original.workNamespace ?? "-"}
      </div>
    ),
  },
  {
    accessorKey: "imageRepository.url",
    header: t("env.col.imageRepo"),
    cell: ({ row }) => (
      <div className="whitespace-normal break-all ">
        {row.original.imageRepository?.url ?? "-"}
      </div>
    ),
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
            <Pencil className="mr-2 h-4 w-4" />
            {t("common.edit")}
          </Button>
        </div>
      )
    },
  },
]
