import { ReactNode } from "react"

interface ContentPageProps {
  title: string
  actions?: ReactNode
  children: ReactNode
}

export function ContentPage({ title, actions, children }: ContentPageProps) {
  return (
    <div className="flex-1 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold tracking-tight">{title}</h2>
        {actions && <div>{actions}</div>}
      </div>
      {children}
    </div>
  )
}
