"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { Pencil, Rocket, Activity, GitBranch } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { AppIdentityMark } from "@/components/app-identity-mark"
import { Application } from "@/lib/api/types"

export const getColumns = (t: (key: string) => string): ColumnDef<Application>[] => [
  {
    accessorKey: "name",
    header: t("apps.col.name"),
    cell: ({ row }) => (
      <div className="flex items-center gap-2">
        <AppIdentityMark seed={row.original} />
        <Copyable value={row.original.name} maxLength={Infinity} className="font-sans" />
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
