"use client"

import { ColumnDef } from "@tanstack/react-table"
import { KeyRound, Pencil } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Copyable } from "@/components/ui/copyable"
import { LocalTime } from "@/components/ui/local-time"
import { User } from "@/lib/api/users"

interface TableMeta {
  onEdit: (user: User) => void
  onChangePassword: (user: User) => void
  isAdmin: boolean
}

export const getColumns = (t: (key: string) => string): ColumnDef<User>[] => [
  {
    accessorKey: "username",
    header: t("users.col.username"),
    size: 240,
    cell: ({ row }) => (
      <div className="flex flex-col gap-0.5">
        <span className="font-medium">{row.original.username}</span>
        <Copyable
          value={row.original.id}
          maxLength={Infinity}
          className="text-xs text-muted-foreground whitespace-nowrap"
        />
      </div>
    ),
  },
  {
    accessorKey: "email",
    header: t("users.col.email"),
    cell: ({ row }) => row.original.email
      ? <Copyable value={row.original.email} maxLength={Infinity} />
      : <span className="text-muted-foreground">-</span>,
  },
  {
    accessorKey: "role",
    header: t("users.col.role"),
    cell: ({ row }) => (
      <span>{row.original.role === "ADMIN" ? t("users.role.admin") : t("users.role.user")}</span>
    ),
  },
  {
    accessorKey: "enabled",
    header: t("users.col.status"),
    cell: ({ row }) => row.original.enabled === false
      ? <Badge variant="secondary" className="text-muted-foreground">{t("users.status.disabled")}</Badge>
      : <Badge variant="outline" className="text-success border-success/40">{t("users.status.enabled")}</Badge>,
  },
  {
    accessorKey: "createdTime",
    header: t("users.col.createdTime"),
    cell: ({ row }) => <LocalTime value={row.original.createdTime} />,
  },
  {
    id: "actions",
    cell: ({ row, table }) => {
      const user = row.original
      const meta = table.options.meta as TableMeta
      if (!meta?.isAdmin) return null
      return (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onEdit(user)}
          >
            <Pencil className="size-4" />
            {t("users.col.edit")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onChangePassword(user)}
          >
            <KeyRound className="size-4" />
            {t("users.col.changePassword")}
          </Button>
        </div>
      )
    },
  },
]
