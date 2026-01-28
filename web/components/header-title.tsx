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
import React from "react"

export function HeaderTitle() {
  const pathname = usePathname()

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
      <Breadcrumb>
        <BreadcrumbList>
          {getBreadcrumbs()}
        </BreadcrumbList>
      </Breadcrumb>
    </header>
  )
}
