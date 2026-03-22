"use client"

import Link from "next/link"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { LogOut, Monitor, Moon, Sun, PanelLeftClose, PanelLeftOpen, MoreHorizontal } from "lucide-react"

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
  useSidebar,
} from "@/components/ui/sidebar"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Button } from "@/components/ui/button"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { navConfig } from "@/lib/nav-config"
import { usePathname } from "next/navigation"
import { useState, useEffect } from "react"
import { clearAuth } from "@/lib/auth"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"
import { useTheme } from "next-themes"

export function AppSidebar() {
  const pathname = usePathname()
  const router = useRouter()
  const { open, toggleSidebar } = useSidebar()
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const { theme, setTheme, resolvedTheme } = useTheme()
  const [mounted, setMounted] = useState(false)

  useEffect(() => setMounted(true), [])

  useEffect(() => {
    getCurrentUser().then(setCurrentUser)
  }, [])

  function handleLogout() {
    clearAuth()
    router.replace("/login")
  }

  function confirmLogout() {
    setLogoutOpen(false)
    handleLogout()
  }

  return (
    <>
    <AlertDialog open={logoutOpen} onOpenChange={setLogoutOpen}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>确认退出登录?</AlertDialogTitle>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction onClick={confirmLogout}>退出登录</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <div className="flex items-center px-1 py-1">
              <Link href="/" className="flex items-center gap-2 min-w-0">
                <div className={`relative aspect-square overflow-hidden shrink-0 ${open ? "size-12 rounded-lg" : "size-6 rounded-md"}`}>
                  <Image src="/icon.png" alt="Oops" fill className="object-cover" />
                </div>
                {open && (
                  <div className="flex flex-col gap-0.5 leading-none min-w-0">
                    <span className="font-bold text-xl">OOPS</span>
                    <span className="text-xs text-sidebar-foreground/60">
                      <span className="font-semibold text-primary">Kubernetes</span> Is All You Need
                    </span>
                  </div>
                )}
              </Link>
            </div>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {navConfig.map((group) => (
          <SidebarGroup key={group.title}>
            {open && <SidebarGroupLabel>{group.title}</SidebarGroupLabel>}
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      asChild
                      isActive={pathname === item.url || pathname.startsWith(item.url + "/")}
                      tooltip={item.title}
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
            <SidebarMenuButton
              onClick={toggleSidebar}
              tooltip={open ? undefined : "展开侧边栏"}
            >
              {open ? <PanelLeftClose /> : <PanelLeftOpen />}
              <span>折叠</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
          <SidebarMenuItem>
            {open ? (
              <div className="flex items-center justify-between px-2 py-1">
                <div className="flex flex-col min-w-0">
                  <span className="text-sm text-muted-foreground truncate">{currentUser?.username}</span>
                  {currentUser?.email && (
                    <span className="text-xs text-muted-foreground/70 truncate">{currentUser.email}</span>
                  )}
                </div>
                <div className="flex items-center gap-1 ml-2 shrink-0">
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-7 w-7 text-muted-foreground hover:text-foreground" aria-label="切换主题">
                        {mounted && resolvedTheme === "dark"
                          ? <Moon className="h-4 w-4" />
                          : <Sun className="h-4 w-4" />}
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" side="top">
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
                  <button onClick={() => setLogoutOpen(true)} className="text-muted-foreground hover:text-foreground transition-colors cursor-pointer" title="退出登录">
                    <LogOut className="h-4 w-4" />
                  </button>
                </div>
              </div>
            ) : (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <SidebarMenuButton tooltip="更多">
                    <MoreHorizontal />
                  </SidebarMenuButton>
                </DropdownMenuTrigger>
                <DropdownMenuContent side="right" align="end">
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
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => setLogoutOpen(true)} className="text-destructive focus:text-destructive">
                    <LogOut className="size-4" />
                    退出登录
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
    </>
  )
}
