"use client"

import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RotateCw, Terminal, FileText } from "lucide-react"

export const getStatusColumns = (
  t: (key: string) => string,
  onRestart: (podName: string) => void,
  onViewLogs: (podName: string) => void,
  onTerminal: (podName: string) => void
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
    accessorKey: "image",
    header: t("apps.status.col.image"),
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
  {
    id: "actions",
    cell: ({ row }) => {
      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant={row.original.status === "Running" ? "warning" : "outline"}
            size="sm"
            onClick={() => onRestart(row.original.name)}
          >
            <RotateCw className="h-4 w-4" />
            <span className="hidden lg:inline">{t("apps.status.col.restart")}</span>
          </Button>
          <Button variant="outline" size="sm" onClick={() => onViewLogs(row.original.name)}>
            <FileText className="h-4 w-4" />
            <span className="hidden lg:inline">{t("apps.status.col.logs")}</span>
          </Button>
          <Button variant="outline" size="sm" onClick={() => onTerminal(row.original.name)}>
            <Terminal className="h-4 w-4" />
            <span className="hidden lg:inline">{t("apps.status.col.terminal")}</span>
          </Button>
        </div>
      )
    },
  },
]
