"use client"

import { memo } from "react"
import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Copyable } from "@/components/ui/copyable"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Check, X } from "lucide-react"
import Link from "next/link"

/**
 * The tag of an image reference, or "" when it carries none. The last colon only starts a tag if it
 * comes after the last slash — a registry host with a port (`registry:5000/app`) has one too.
 */
export function imageTag(image?: string | null): string {
  if (!image) return ""
  const colon = image.lastIndexOf(":")
  return colon > image.lastIndexOf("/") ? image.slice(colon + 1) : ""
}

interface DeployStatusCellProps {
  images: string[]
  namespace: string
  appName: string
  expectedTag: string
}

const DeployStatusCell = memo(({ images, namespace, appName, expectedTag }: DeployStatusCellProps) => {
  const firstImage = images.length > 0 ? images[0] : ""
  const tag = imageTag(firstImage)
  const versionMached = !!tag && tag === expectedTag
  const icon = !tag ? null : versionMached ? (
    <Check className="size-4 text-success" />
  ) : (
    <Link href={`/apps/${namespace}/${appName}/pipelines/${tag}`}>
      <X className="size-4 text-destructive cursor-pointer" />
    </Link>
  )
  return (
    <div className="flex items-center gap-1.5">
      {tag && (
        <Tooltip>
          <TooltipTrigger render={<span />}>
            <Copyable value={tag} copyValue={firstImage} maxLength={tag.length} displayClassName="text-xs text-muted-foreground" />
          </TooltipTrigger>
          <TooltipContent className="w-fit max-w-160 break-all">
            <div className="flex flex-col gap-1">
              {images.map((img) => (
                <span key={img}>{img}</span>
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
  // The tag this pipeline put in the cluster, not its id: an image is tagged with the id of the
  // pipeline that BUILT it, and a rollback redeploys its source's image untouched. Comparing the
  // pod against the id would mark every rollback as running the wrong version.
  expectedTag: string
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
        <Badge variant={status === "Running" ? "info" : "destructive"}>
          {status}
        </Badge>
      )
    },
  },
  {
    id: "deployStatus",
    header: t("apps.pipeline.col.currentVersion"),
    cell: ({ row }) => {
      const images = (row.original.containers ?? []).map((c) => c.image)
      return (
        <DeployStatusCell
          images={images}
          namespace={namespace}
          appName={appName}
          expectedTag={expectedTag}
        />
      )
    },
  },
]
