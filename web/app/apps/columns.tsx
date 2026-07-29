"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Rocket, Activity, GitBranch, ArrowRight } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card"
import { LocalTime } from "@/components/ui/local-time"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { AppIdentityMark } from "@/components/app-identity-mark"
import { ActiveDeployment, Application } from "@/lib/api/types"

// Keys the active-deployment lookup the list polls; an application is identified by
// namespace and name, since the "all" scope mixes namespaces on one page.
export const applicationKey = (namespace: string, name: string) => `${namespace}/${name}`

// Marks an application whose pipeline is still in flight, and lists the deploying
// environments — each linking to its pipeline detail — when hovered.
function DeployingMark({
  application,
  deployments,
  t,
}: {
  application: Application
  deployments: ActiveDeployment[]
  t: (key: string) => string
}) {
  if (deployments.length === 0) return null

  const statusLabel = (deployment: ActiveDeployment) =>
    t(`apps.pipeline.status.${deployment.status}`)

  return (
    <HoverCard>
      <HoverCardTrigger
        render={
          <Badge variant="info" className="cursor-pointer gap-1.5">
            <span className="relative flex size-1.5">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-75" />
              <span className="relative inline-flex size-1.5 rounded-full bg-primary" />
            </span>
            {t("apps.deploying.mark")}
          </Badge>
        }
      />
      <HoverCardContent align="start" className="w-72 p-3">
        <p className="mb-2 text-xs font-medium text-muted-foreground">{t("apps.deploying.title")}</p>
        <div className="flex flex-col gap-1">
          {deployments.map((deployment) => (
            <Link
              key={deployment.pipelineId}
              href={`/apps/${application.namespace}/${application.name}/pipelines/${deployment.pipelineId}`}
              className="group flex items-center justify-between gap-2 rounded-md px-2 py-1.5 transition-colors cursor-pointer hover:bg-muted"
            >
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-sm font-medium">{deployment.environment}</span>
                <LocalTime value={deployment.createdTime} className="text-xs text-muted-foreground" />
              </div>
              <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                {statusLabel(deployment)}
                <ArrowRight className="size-3 transition-transform group-hover:translate-x-0.5" />
              </span>
            </Link>
          ))}
        </div>
      </HoverCardContent>
    </HoverCard>
  )
}

export const getColumns = (
  t: (key: string) => string,
  activeDeployments: Record<string, ActiveDeployment[]> = {}
): ColumnDef<Application>[] => [
  {
    accessorKey: "name",
    header: t("apps.col.name"),
    cell: ({ row }) => (
      <div className="flex items-center gap-2">
        <AppIdentityMark seed={row.original} />
        <Copyable value={row.original.name} maxLength={Infinity} className="font-sans" />
        <DeployingMark
          application={row.original}
          deployments={activeDeployments[applicationKey(row.original.namespace, row.original.name)] ?? []}
          t={t}
        />
      </div>
    ),
  },
  {
    accessorKey: "description",
    header: t("apps.col.description"),
    cell: ({ row }) => {
      const desc = row.original.description
      if (!desc) return null
      return (
        <Tooltip>
          <TooltipTrigger render={<span className="block max-w-48 truncate cursor-default" />}>
            {desc}
          </TooltipTrigger>
          <TooltipContent>
            <p className="max-w-sm whitespace-pre-wrap">{desc}</p>
          </TooltipContent>
        </Tooltip>
      )
    },
  },
  {
    accessorKey: "namespace",
    header: t("apps.col.namespace"),
  },
  {
    accessorKey: "owner",
    header: t("apps.col.owner"),
    cell: ({ row }) => {
      if (!row.original.owner) {
        return t("common.unassigned")
      }
      if (!row.original.ownerName) {
        return (
          <Tooltip>
            <TooltipTrigger className="cursor-help">
              <span className="text-muted-foreground">{t("common.deletedUser")}</span>
            </TooltipTrigger>
            <TooltipContent>
              <p>{row.original.owner}</p>
            </TooltipContent>
          </Tooltip>
        )
      }
      return row.original.ownerName
    },
  },
  {
    id: "actions",
    cell: ({ row }) => {
      const application = row.original
      const base = `/apps/${application.namespace}/${application.name}`
      return (
        <div className="flex items-center justify-end gap-2">
          <Button render={<Link href={base} title={t("apps.col.edit")} />} variant="outline" size="sm">
            <Pencil className="size-4" />
            {t("apps.col.edit")}
          </Button>
          <Button render={<Link href={`${base}/publish`} title={t("apps.col.publish")} />} variant="outline" size="sm">
            <Rocket className="size-4" />
            {t("apps.col.publish")}
          </Button>
          <Button render={<Link href={`${base}/status`} title={t("apps.col.status")} />} variant="outline" size="sm">
            <Activity className="size-4" />
            {t("apps.col.status")}
          </Button>
          <Button
            render={
              <Link
                href={`/pipelines?namespace=${application.namespace}&app=${application.name}`}
                title={t("apps.col.pipelines")}
              />
            }
            variant="outline"
            size="sm"
          >
            <GitBranch className="size-4" />
            {t("apps.col.pipelines")}
          </Button>
        </div>
      )
    },
  },
]
