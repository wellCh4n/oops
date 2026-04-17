"use client"

import { SidebarTrigger } from "@/components/ui/sidebar"
import { Separator } from "@/components/ui/separator"
import { usePathname } from "next/navigation"
import { navConfig } from "@/lib/nav-config"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Monitor, Moon, Sun } from "lucide-react"
import { useTheme } from "next-themes"

export function HeaderTitle() {
  const pathname = usePathname()
  const { theme, setTheme, resolvedTheme } = useTheme()

  const getCurrentTitle = () => {
    if (pathname === "/") {
      return "首页"
    }
    for (const group of navConfig) {
      if (group.url === pathname) {
        return group.title
      }
      const item = group.items.find((item) => item.url === pathname || pathname.startsWith(item.url + "/"))
      if (item) {
        return item.title
      }
    }
    return "页面"
  }

  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4 bg-background min-w-[720px]">
      <SidebarTrigger className="-ml-1" />
      <Separator orientation="vertical" className="mr-2 h-4" />
      <div className="flex-1 h-8 rounded-md bg-muted px-3 flex items-center">
        <span className="text-sm font-medium text-muted-foreground truncate">{getCurrentTitle()}</span>
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            aria-label="切换主题"
          >
            {resolvedTheme === "dark" ? <Moon className="size-4" /> : <Sun className="size-4" />}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuRadioGroup value={theme ?? "system"} onValueChange={setTheme}>
            <DropdownMenuRadioItem value="system">
              <Monitor className="size-4" />
              系统
            </DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="light">
              <Sun className="size-4" />
              明亮
            </DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="dark">
              <Moon className="size-4" />
              暗黑
            </DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </header>
  )
}
