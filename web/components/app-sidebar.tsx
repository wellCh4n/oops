"use client"

import Link from "next/link"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { LogOut } from "lucide-react"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from "@/components/ui/sidebar"
import { navConfig } from "@/lib/nav-config"
import { usePathname } from "next/navigation"
import { useState, useEffect } from "react"
import { clearAuth } from "@/lib/auth"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"

export function AppSidebar() {
  const pathname = usePathname()
  const router = useRouter()
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)

  useEffect(() => {
    getCurrentUser().then(setCurrentUser)
  }, [])

  function handleLogout() {
    clearAuth()
    router.replace("/login")
  }

  return (
    <Sidebar>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              size="lg"
              asChild
              className="hover:bg-transparent hover:text-sidebar-foreground"
            >
              <Link href="/">
                <div className="relative aspect-square size-10 overflow-hidden rounded-lg">
                  <Image src="/icon.png" alt="Oops" fill className="object-cover" />
                </div>
                <div className="flex flex-col gap-0.5 leading-none">
                  <span className="font-bold text-xl">OOPS</span>
                  <span className="text-xs text-sidebar-foreground/60">
                    <span className="font-semibold text-primary">Kubernetes</span> Is All You Need
                  </span>
                </div>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {navConfig.map((group) => (
          <SidebarGroup key={group.title}>
            <SidebarGroupLabel>{group.title}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      asChild
                      isActive={pathname === item.url || pathname.startsWith(item.url + "/")}
                    >
                      <Link href={item.url}>
                        <item.icon />
                        <span>{item.title}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <div className="flex items-center justify-between px-2 py-1">
              <div className="flex flex-col min-w-0">
                <span className="text-sm text-muted-foreground truncate">{currentUser?.username}</span>
                {currentUser?.email && (
                  <span className="text-xs text-muted-foreground/70 truncate">{currentUser.email}</span>
                )}
              </div>
              <button onClick={handleLogout} className="text-muted-foreground hover:text-foreground transition-colors ml-2 shrink-0 cursor-pointer" title="退出登录">
                <LogOut className="h-4 w-4" />
              </button>
            </div>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
