"use client"

import { ColumnDef } from "@tanstack/react-table"
import { ApplicationPodStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RotateCw, Terminal, FileText } from "lucide-react"

export const getStatusColumns = (
  onRestart: (podName: string) => void,
  onViewLogs: (podName: string) => void,
  onTerminal: (podName: string) => void
): ColumnDef<ApplicationPodStatus>[] => [
  {
    accessorKey: "name",
    header: "Pod",
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
    accessorKey: "podIP",
    header: "IP 地址",
  },
  {
    accessorKey: "image",
    header: "镜像",
    cell: ({ row }) => {
      const images = row.getValue("image") as string[]
      return (
        <div className="flex flex-col gap-1">
          {images.map((img, i) => (
            <span key={i} className="text-sm text-muted-foreground break-all">
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
            variant="outline" 
            size="sm" 
            onClick={() => onRestart(row.original.name)}
          >
            <RotateCw className="mr-2 h-4 w-4" />
            重启
          </Button>
          <Button variant="outline" size="sm" onClick={() => onViewLogs(row.original.name)}>
            <FileText className="mr-2 h-4 w-4" />
            日志
          </Button>
          <Button variant="outline" size="sm" onClick={() => onTerminal(row.original.name)}>
            <Terminal className="mr-2 h-4 w-4" />
            终端
          </Button>
        </div>
      )
    },
  },
]
