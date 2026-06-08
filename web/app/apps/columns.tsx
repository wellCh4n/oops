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
          <Button asChild variant="outline" size="sm">
            <Link href={base} title={t("apps.col.edit")}>
              <Pencil className="size-4" />
              {t("apps.col.edit")}
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${base}/publish`} title={t("apps.col.publish")}>
              <Rocket className="size-4" />
              {t("apps.col.publish")}
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${base}/status`} title={t("apps.col.status")}>
              <Activity className="size-4" />
              {t("apps.col.status")}
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link
              href={`/pipelines?namespace=${application.namespace}&app=${application.name}`}
              title={t("apps.col.pipelines")}
            >
              <GitBranch className="size-4" />
              {t("apps.col.pipelines")}
            </Link>
          </Button>
        </div>
      )
    },
  },
]
