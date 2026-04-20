import { ReactNode } from "react"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { cn } from "@/lib/utils"

interface ContentPageProps {
  title: string
  actions?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
  disableGutter?: boolean
  fullHeight?: boolean
}

export function ContentPage({
  title,
  actions,
  children,
  className,
  bodyClassName,
  disableGutter = false,
  fullHeight = false,
}: ContentPageProps) {
  return (
    <div
      className={cn(
        "flex w-full flex-col min-h-0",
        (disableGutter || fullHeight) && "flex-1",
        className
      )}
    >
      <div
        className={cn(
          "sticky top-0 z-10 shrink-0 bg-sidebar/95 border-b border-sidebar-border px-4 py-2 flex items-center justify-between backdrop-blur supports-[backdrop-filter]:bg-sidebar/85",
          disableGutter ? "w-full" : "-mx-4 -mt-4 w-[calc(100%+2rem)]"
        )}
      >
        <div className="flex items-center gap-2">
          <SidebarTrigger className="md:hidden -ml-1" />
          <h2 className="text-sm font-medium text-sidebar-foreground/80 tracking-normal">{title}</h2>
        </div>
        {actions && <div>{actions}</div>}
      </div>
      {disableGutter ? (
        <div className={cn("flex-1 min-h-0", bodyClassName)}>
          {children}
        </div>
      ) : fullHeight ? (
        <div className={cn("flex flex-1 min-h-0 flex-col pt-4", bodyClassName)}>
          {children}
        </div>
      ) : (
        <div className={cn("pt-4 pb-4", bodyClassName)}>
          {children}
        </div>
      )}
    </div>
  )
}
