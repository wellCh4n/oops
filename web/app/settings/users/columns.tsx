"use client"

import { ColumnDef } from "@tanstack/react-table"
import { KeyRound, Pencil, Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Copyable } from "@/components/ui/copyable"

export interface User {
  id: string
  username: string
  email: string | null
  role: string
  createdTime: string
}

interface TableMeta {
  onEdit: (user: User) => void
  onChangePassword: (user: User) => void
  onDelete: (user: User) => void
  isAdmin: boolean
}

export const getColumns = (t: (key: string) => string): ColumnDef<User>[] => [
  {
    accessorKey: "id",
    header: "ID",
    size: 300,
    cell: ({ row }) => (
      <div className="whitespace-nowrap">
        <Copyable value={row.original.id} maxLength={Infinity} />
      </div>
    ),
  },
  {
    accessorKey: "username",
    header: t("users.col.username"),
    cell: ({ row }) => <Copyable value={row.original.username} maxLength={20} />,
  },
  {
    accessorKey: "email",
    header: t("users.col.email"),
    cell: ({ row }) => row.original.email
      ? <Copyable value={row.original.email} maxLength={30} />
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
    accessorKey: "createdTime",
    header: t("users.col.createdTime"),
    cell: ({ row }) => (
      <span>
        {row.original.createdTime
          ? new Date(row.original.createdTime).toLocaleString()
          : "-"}
      </span>
    ),
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
            <Pencil className="mr-2 h-4 w-4" />
            {t("users.col.edit")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onChangePassword(user)}
          >
            <KeyRound className="mr-2 h-4 w-4" />
            {t("users.col.changePassword")}
          </Button>
          {user.role !== "ADMIN" && (
            <Button
              variant="destructive"
              size="sm"
              onClick={() => meta?.onDelete(user)}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              {t("users.col.delete")}
            </Button>
          )}
        </div>
      )
    },
  },
]
