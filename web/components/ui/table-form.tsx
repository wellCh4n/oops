import { ReactNode } from "react"

interface TableFormProps {
  options?: ReactNode
  table: ReactNode
}

export function TableForm({ options, table }: TableFormProps) {
  return (
    <div className="space-y-4">
      {options && <div>{options}</div>}
      <div>{table}</div>
    </div>
  )
}
