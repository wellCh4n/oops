"use client"

import { memo } from "react"
import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Copyable } from "@/components/ui/copyable"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Check, X } from "lucide-react"
import Link from "next/link"
import { applicationPipelinePath } from "@/lib/routes"

interface DeployStatusCellProps {
  images: string[]
  namespace: string
  appName: string
  pipelineId: string
}

const DeployStatusCell = memo(({ images, namespace, appName, pipelineId }: DeployStatusCellProps) => {
  const tag = images.length > 0 && images[0].includes(":") ? images[0].split(":").pop()! : ""
  const versionMached = tag === pipelineId
  const icon = !tag ? null : versionMached ? (
    <Check className="h-4 w-4 text-green-500" />
  ) : (
    <Link href={applicationPipelinePath(namespace, appName, tag)}>
      <X className="h-4 w-4 text-red-500 cursor-pointer" />
    </Link>
  )
  return (
    <div className="flex items-center gap-1.5">
      {tag && (
        <Tooltip>
          <TooltipTrigger asChild>
            <span>
              <Copyable value={tag} copyValue={images[0]} maxLength={tag.length} displayClassName="text-xs text-muted-foreground" />
            </span>
          </TooltipTrigger>
          <TooltipContent className="w-fit max-w-160 break-all">
            <div className="flex flex-col gap-1">
              {images.map((img, i) => (
                <span key={i}>{img}</span>
              ))}
            </div>
          </TooltipContent>
        </Tooltip>
      )}
      {icon}
    </div>
  )
})
DeployStatusCell.displayName = "DeployStatusCell"

export const getPipelineStatusColumns = (
  t: (key: string) => string,
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
    header: t("apps.pipeline.col.status"),
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
    header: t("apps.pipeline.col.currentVersion"),
    cell: ({ row }) => {
      const images = row.original.image ?? []
      return (
        <DeployStatusCell
          images={images}
          namespace={namespace}
          appName={appName}
          pipelineId={pipelineId}
        />
      )
    },
  },
]
