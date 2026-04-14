import { ReactNode } from "react"

interface ContentPageProps {
  title: string
  actions?: ReactNode
  children: ReactNode
}

export function ContentPage({ title, actions, children }: ContentPageProps) {
  return (
    <div className="flex flex-col flex-1 gap-4 min-h-0">
      <div className="-mx-4 -mt-4 w-[calc(100%+2rem)] bg-sidebar border-b border-sidebar-border px-4 py-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-sidebar-foreground/80 tracking-normal">{title}</h2>
        {actions && <div>{actions}</div>}
      </div>
      {children}
    </div>
  )
}
