"use client"

import Link from "next/link"
import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RotateCw, Terminal, FileText } from "lucide-react"

interface PodLinkContext {
  namespace: string
  applicationName: string
  env: string
}

export const getStatusColumns = (
  t: (key: string) => string,
  onRestart: (podName: string) => void,
  linkContext: PodLinkContext
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
    header: t("apps.status.col.status"),
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
    accessorKey: "podIP",
    header: t("apps.status.col.ip"),
  },
  {
    accessorKey: "nodeName",
    header: t("apps.status.col.node"),
  },
  {
    id: "actions",
    cell: ({ row }) => {
      const podName = row.original.name
      const podPath = `/apps/${linkContext.namespace}/${linkContext.applicationName}/pods/${podName}`
      const query = `?env=${encodeURIComponent(linkContext.env)}`
      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant={row.original.status === "Running" ? "warning" : "outline"}
            size="sm"
            onClick={() => onRestart(podName)}
          >
            <RotateCw className="size-4" />
            <span className="hidden lg:inline">{t("apps.status.col.restart")}</span>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${podPath}/logs${query}`}>
              <FileText className="size-4" />
              <span className="hidden lg:inline">{t("apps.status.col.logs")}</span>
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`${podPath}/terminal${query}`}>
              <Terminal className="size-4" />
              <span className="hidden lg:inline">{t("apps.status.col.terminal")}</span>
            </Link>
          </Button>
        </div>
      )
    },
  },
]
