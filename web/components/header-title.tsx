"use client"

import { SidebarTrigger } from "@/components/ui/sidebar"
import { Separator } from "@/components/ui/separator"
import { usePathname } from "next/navigation"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { navConfig } from "@/lib/nav-config"
import React, { useEffect, useState } from "react"
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
  const [mounted, setMounted] = useState(false)
  useEffect(() => setMounted(true), [])

  const getBreadcrumbs = () => {
    const items = [{ title: "首页", url: "/" }]

    for (const group of navConfig) {
      if (group.url === pathname) {
        items.push({ title: group.title, url: group.url })
        break
      }
      const item = group.items.find((item) => item.url === pathname || pathname.startsWith(item.url + "/"))
      if (item) {
        items.push({ title: group.title, url: group.url || "" })
        items.push({ title: item.title, url: item.url })
        break
      }
    }

    return items.map((item, index) => {
      const isLast = index === items.length - 1
      return (
        <React.Fragment key={item.title}>
          {index > 0 && <BreadcrumbSeparator />}
          <BreadcrumbItem>
            {isLast ? (
              <BreadcrumbPage>{item.title}</BreadcrumbPage>
            ) : item.url ? (
              <BreadcrumbLink href={item.url}>{item.title}</BreadcrumbLink>
            ) : (
              <span>{item.title}</span>
            )}
          </BreadcrumbItem>
        </React.Fragment>
      )
    })
  }

  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4 bg-background">
      <SidebarTrigger className="-ml-1" />
      <Separator orientation="vertical" className="mr-2 h-4" />
      <Breadcrumb className="flex-1">
        <BreadcrumbList>
          {getBreadcrumbs()}
        </BreadcrumbList>
      </Breadcrumb>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            aria-label="切换主题"
          >
            {mounted && resolvedTheme === "dark" ? <Moon className="size-4" /> : <Sun className="size-4" />}
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
