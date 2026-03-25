"use client"

import {
  ColumnDef,
  TableMeta,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table"

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[]
  data: TData[]
  meta?: TableMeta<TData>
  loading?: boolean
  getRowId?: (row: TData) => string
}

export function DataTable<TData, TValue>({
  columns,
  data,
  meta,
  loading,
  getRowId,
}: DataTableProps<TData, TValue>) {
  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
    meta,
    ...(getRowId ? { getRowId } : {}),
  })

  const renderBody = () => {
    if (loading) {
      return (
        <TableRow>
          <TableCell colSpan={columns.length} className="py-2 text-center text-muted-foreground">
            加载中...
          </TableCell>
        </TableRow>
      )
    }
    if (!table.getRowModel().rows?.length) {
      return (
        <TableRow>
          <TableCell colSpan={columns.length} className="h-24 text-center text-muted-foreground">
            暂无数据
          </TableCell>
        </TableRow>
      )
    }
    return table.getRowModel().rows.map((row) => (
      <TableRow key={row.id} data-state={row.getIsSelected() && "selected"}>
        {row.getVisibleCells().map((cell) => (
          <TableCell
            key={cell.id}
            style={cell.column.columnDef.size !== undefined ? { width: cell.column.columnDef.size, minWidth: cell.column.columnDef.size } : undefined}
          >
            {flexRender(cell.column.columnDef.cell, cell.getContext())}
          </TableCell>
        ))}
      </TableRow>
    ))
  }

  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {headerGroup.headers.map((header) => (
                <TableHead
                  key={header.id}
                  style={header.column.columnDef.size !== undefined ? { width: header.column.columnDef.size, minWidth: header.column.columnDef.size } : undefined}
                >
                  {header.isPlaceholder
                    ? null
                    : flexRender(header.column.columnDef.header, header.getContext())}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {renderBody()}
        </TableBody>
      </Table>
    </div>
  )
}
