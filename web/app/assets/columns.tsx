"use client"

import { ColumnDef } from "@tanstack/react-table"
import NextImage from "next/image"
import { Folder, FileText, Download, Link2, Trash2 } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { LocalTime } from "@/components/ui/local-time"
import { AssetEntry } from "@/lib/api/assets"

interface TableMeta {
  onOpen: (entry: AssetEntry) => void
  onDelete: (entry: AssetEntry) => void
  isAdmin: boolean
  t: (key: string) => string
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return "0 B"
  const units = ["B", "KB", "MB", "GB", "TB"]
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / Math.pow(1024, exponent)
  return `${value.toFixed(exponent === 0 ? 0 : 1)} ${units[exponent]}`
}

function entryUrl(entry: AssetEntry): string {
  return entry.publicUrl ?? entry.signedUrl ?? ""
}

export const getColumns = (t: (key: string) => string): ColumnDef<AssetEntry>[] => [
  {
    accessorKey: "name",
    header: t("assets.col.fileName"),
    cell: ({ row, table }) => {
      const entry = row.original
      const meta = table.options.meta as TableMeta
      if (entry.type === "FOLDER") {
        return (
          <button
            type="button"
            onClick={() => meta.onOpen(entry)}
            className="flex items-center gap-2 cursor-pointer hover:underline"
          >
            <Folder className="size-4 text-sky-500" />
            <span className="font-medium">{entry.name}</span>
          </button>
        )
      }
      const isImage = (entry.contentType ?? "").startsWith("image/")
      return (
        <div className="flex items-center gap-2">
          {isImage ? (
            <NextImage
              src={entryUrl(entry)}
              alt={entry.name}
              width={32}
              height={32}
              unoptimized
              className="size-8 rounded-sm object-cover border"
            />
          ) : (
            <FileText className="size-4 text-muted-foreground" />
          )}
          <span className="font-medium">{entry.name}</span>
        </div>
      )
    },
  },
  {
    accessorKey: "size",
    header: t("assets.col.size"),
    cell: ({ row }) => row.original.type === "FOLDER"
      ? <span className="text-muted-foreground">-</span>
      : <span className="text-sm">{formatBytes(row.original.size)}</span>,
  },
  {
    accessorKey: "contentType",
    header: t("assets.col.type"),
    cell: ({ row }) => row.original.type === "FOLDER"
      ? <span className="text-muted-foreground text-sm">{t("assets.folder")}</span>
      : <span className="text-muted-foreground text-sm">{row.original.contentType}</span>,
  },
  {
    accessorKey: "lastModified",
    header: t("assets.col.lastModified"),
    cell: ({ row }) => row.original.lastModified
      ? <LocalTime value={row.original.lastModified} />
      : <span className="text-muted-foreground">-</span>,
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const entry = row.original
      const meta = table.options.meta as TableMeta
      return (
        <div className="flex items-center justify-end gap-2">
          {entry.type === "FILE" && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={async () => {
                  await navigator.clipboard.writeText(entryUrl(entry))
                  toast.success(meta.t("assets.urlCopied"))
                }}
              >
                <Link2 className="size-4" />
                {meta.t("assets.copyUrl")}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => window.open(entryUrl(entry), "_blank")}
              >
                <Download className="size-4" />
                {meta.t("assets.download")}
              </Button>
            </>
          )}
          {meta.isAdmin && (
            <Button
              variant="outline"
              size="sm"
              className="text-destructive hover:text-destructive"
              onClick={() => meta.onDelete(entry)}
            >
              <Trash2 className="size-4" />
              {meta.t("common.delete")}
            </Button>
          )}
        </div>
      )
    },
  },
]
