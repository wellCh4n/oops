"use client"

import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Check, X } from "lucide-react"
import Link from "next/link"

export const getPipelineStatusColumns = (
  namespace: string,
  appName: string,
  pipelineId: string
): ColumnDef<ApplicationPodStatus>[] => [
  {
    accessorKey: "name",
    header: "Pod",
    cell: ({ row }) => {
      const name = row.getValue("name") as string
      return <div className="max-w-48 truncate">{name}</div>
    },
  },
  {
    accessorKey: "status",
    header: "状态",
    cell: ({ row }) => {
      const status = row.getValue("status") as string
      return (
        <Badge variant={status === "Running" ? "default" : "destructive"}>
          {status}
        </Badge>
      )
    },
  },
  {
    id: "deployStatus",
    header: "是否当前版本",
    cell: ({ row }) => {
      const images = row.original.image ?? []
      const tag = images.length > 0 && images[0].includes(":") ? images[0].split(":").pop()! : ""
      if (!tag) return null
      const versionMached = tag === pipelineId
      return versionMached ? (
        <Check className="h-4 w-4 text-green-500" />
      ) : (
        <Link href={`/apps/${namespace}/${appName}/pipelines/${tag}`}>
          <X className="h-4 w-4 text-red-500 cursor-pointer" />
        </Link>
      )
    },
  },
  {
    accessorKey: "image",
    header: "镜像",
    cell: ({ row }) => {
      const images = row.getValue("image") as string[]
      return (
        <div className="flex max-w-[28rem] flex-col gap-1 whitespace-normal break-all">
          {images.map((img, i) => (
            <span key={i} className="text-sm text-muted-foreground">
              {img}
            </span>
          ))}
        </div>
      )
    },
  },
]
