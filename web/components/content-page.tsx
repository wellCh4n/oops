import { ReactNode } from "react"
import { cn } from "@/lib/utils"

interface ContentPageProps {
  title: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}

export function ContentPage({ title, actions, children, className }: ContentPageProps) {
  return (
    <div className={cn("flex flex-col flex-1 min-h-0 -mb-4", className)}>
      <div className="-mx-4 -mt-4 w-[calc(100%+2rem)] shrink-0 bg-sidebar border-b border-sidebar-border px-4 py-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-sidebar-foreground/80 tracking-normal">{title}</h2>
        {actions && <div>{actions}</div>}
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="pt-4 pb-4">
          {children}
        </div>
      </div>
    </div>
  )
}
