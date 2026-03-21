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

export const columns: ColumnDef<User>[] = [
  {
    accessorKey: "id",
    header: "ID",
    cell: ({ row }) => (
      <div className="whitespace-normal break-all">
        <Copyable value={row.original.id} />
      </div>
    ),
  },
  {
    accessorKey: "username",
    header: "用户名",
    cell: ({ row }) => <Copyable value={row.original.username} maxLength={20} />,
  },
  {
    accessorKey: "email",
    header: "邮箱",
    cell: ({ row }) => row.original.email
      ? <Copyable value={row.original.email} maxLength={30} />
      : <span className="text-muted-foreground">-</span>,
  },
  {
    accessorKey: "role",
    header: "角色",
    cell: ({ row }) => (
      <span>{row.original.role === "ADMIN" ? "管理员" : "普通用户"}</span>
    ),
  },
  {
    accessorKey: "createdTime",
    header: "创建时间",
    cell: ({ row }) => (
      <span>
        {row.original.createdTime
          ? new Date(row.original.createdTime).toLocaleString("zh-CN")
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
            编辑
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => meta?.onChangePassword(user)}
          >
            <KeyRound className="mr-2 h-4 w-4" />
            修改密码
          </Button>
          {user.role !== "ADMIN" && (
            <Button
              variant="destructive"
              size="sm"
              onClick={() => meta?.onDelete(user)}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              删除
            </Button>
          )}
        </div>
      )
    },
  },
]
