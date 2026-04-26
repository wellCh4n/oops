"use client"

import { Fragment, ReactNode, useEffect, useState } from "react"
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
import { Button } from "@/components/ui/button"
import { useLanguage } from "@/contexts/language-context"
import { ChevronDown, ChevronUp } from "lucide-react"

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[]
  data: TData[]
  meta?: TableMeta<TData>
  loading?: boolean
  getRowId?: (row: TData) => string
  renderExpandedRow?: (row: TData) => ReactNode
}

export function DataTable<TData, TValue>({
  columns,
  data,
  meta,
  loading,
  getRowId,
  renderExpandedRow,
}: DataTableProps<TData, TValue>) {
  const { t } = useLanguage()
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

  useEffect(() => {
    if (data.length > 0 && renderExpandedRow) {
      setExpanded((prev) => {
        const next: Record<string, boolean> = {}
        data.forEach((item, index) => {
          const id = getRowId ? getRowId(item) : String(index)
          next[id] = prev[id] ?? true
        })
        return next
      })
    }
  }, [data, getRowId, renderExpandedRow])

  // TanStack Table returns non-memoizable functions; React Compiler skips this component intentionally.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
    meta,
    ...(getRowId ? { getRowId } : {}),
  })

  const toggleRow = (rowId: string) => {
    setExpanded((prev) => ({ ...prev, [rowId]: !prev[rowId] }))
  }

  const renderBody = () => {
    if (loading) {
      return (
        <TableRow>
          <TableCell colSpan={columns.length} className="px-4 py-2 text-center text-muted-foreground">
            {t("common.loading")}
          </TableCell>
        </TableRow>
      )
    }
    if (!table.getRowModel().rows?.length) {
      return (
        <TableRow>
          <TableCell colSpan={columns.length} className="h-24 px-4 text-center text-muted-foreground">
            {t("common.noData")}
          </TableCell>
        </TableRow>
      )
    }
    return table.getRowModel().rows.map((row) => (
      <Fragment key={row.id}>
        <TableRow key={row.id} data-state={row.getIsSelected() && "selected"}>
          {row.getVisibleCells().map((cell, cellIndex) => (
            <TableCell
              key={cell.id}
              className="px-4 py-2"
              style={cell.column.columnDef.size !== undefined ? { width: cell.column.columnDef.size, minWidth: cell.column.columnDef.size } : undefined}
            >
              {cellIndex === 0 && renderExpandedRow ? (
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 w-6 p-0 shrink-0 -ml-2"
                    onClick={() => toggleRow(row.id)}
                  >
                    {expanded[row.id] ? (
                      <ChevronUp className="h-3.5 w-3.5" />
                    ) : (
                      <ChevronDown className="h-3.5 w-3.5" />
                    )}
                  </Button>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </div>
              ) : (
                flexRender(cell.column.columnDef.cell, cell.getContext())
              )}
            </TableCell>
          ))}
        </TableRow>
        {expanded[row.id] && renderExpandedRow && (
          <TableRow>
            <TableCell colSpan={columns.length} className="p-0">
              <div className="px-4 py-3 bg-muted/30">
                {renderExpandedRow(row.original)}
              </div>
            </TableCell>
          </TableRow>
        )}
      </Fragment>
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
                  className="h-10 px-4"
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
