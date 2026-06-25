"use client"

import { ReactNode } from "react"
import { useLanguage } from "@/contexts/language-context"

export interface FieldRow {
  name: string
  type: string
  required?: boolean
  description?: ReactNode
}

export function FieldTable({ rows }: { rows: FieldRow[] }) {
  const { t } = useLanguage()
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full text-sm">
        <thead className="bg-muted/50">
          <tr className="text-left">
            <th className="px-3 py-2 font-medium w-1/4">{t("doc.field")}</th>
            <th className="px-3 py-2 font-medium w-1/4">{t("doc.type")}</th>
            <th className="px-3 py-2 font-medium">{t("doc.description")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name} className="border-t align-top">
              <td className="px-3 py-2 font-mono text-xs whitespace-nowrap">
                {row.name}
                {row.required && <span className="ml-1 text-rose-500">*</span>}
              </td>
              <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{row.type}</td>
              <td className="px-3 py-2 text-muted-foreground">{row.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
