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
import React, { useState, useEffect } from "react"
import { clearAuth } from "@/lib/auth"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"
import { useTheme } from "next-themes"
import { useLanguage } from "@/contexts/language-context"
import { Locale } from "@/lib/i18n"

export function AppSidebar() {
  const pathname = usePathname()
  const router = useRouter()
  const { open, toggleSidebar } = useSidebar()
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const { theme, setTheme } = useTheme()
  const { locale, setLocale, t } = useLanguage()

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
          <AlertDialogTitle>{t("sidebar.logoutConfirm")}</AlertDialogTitle>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>{t("sidebar.cancel")}</AlertDialogCancel>
          <AlertDialogAction onClick={confirmLogout}>{t("sidebar.logout")}</AlertDialogAction>
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
          <React.Fragment key={group.title}>
            <SidebarGroup>
              {open && <SidebarGroupLabel>{t(group.title)}</SidebarGroupLabel>}
              <SidebarGroupContent>
                <SidebarMenu>
                  {group.items.map((item) => (
                    <SidebarMenuItem key={item.title}>
                      <SidebarMenuButton
                        asChild
                        isActive={pathname === item.url || pathname.startsWith(item.url + "/")}
                        tooltip={t(item.title)}
                      >
                        <Link href={item.url}>
                          <item.icon />
                          <span>{t(item.title)}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          </React.Fragment>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              onClick={toggleSidebar}
              tooltip={open ? undefined : t("sidebar.expand")}
            >
              {open ? <PanelLeftClose /> : <PanelLeftOpen />}
              <span>{t("sidebar.collapse")}</span>
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
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-7 w-7 ml-2 shrink-0 text-muted-foreground hover:text-foreground" aria-label={t("sidebar.more")}>
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" side="top">
                    <DropdownMenuRadioGroup value={locale} onValueChange={(v) => setLocale(v as Locale)}>
                      <DropdownMenuRadioItem value="zh">{t("lang.zh")}</DropdownMenuRadioItem>
                      <DropdownMenuRadioItem value="en">{t("lang.en")}</DropdownMenuRadioItem>
                    </DropdownMenuRadioGroup>
                    <DropdownMenuSeparator />
                    <DropdownMenuRadioGroup value={theme ?? "system"} onValueChange={setTheme}>
                      <DropdownMenuRadioItem value="system">
                        <Monitor className="size-4" />
                        {t("sidebar.themeSystem")}
                      </DropdownMenuRadioItem>
                      <DropdownMenuRadioItem value="light">
                        <Sun className="size-4" />
                        {t("sidebar.themeLight")}
                      </DropdownMenuRadioItem>
                      <DropdownMenuRadioItem value="dark">
                        <Moon className="size-4" />
                        {t("sidebar.themeDark")}
                      </DropdownMenuRadioItem>
                    </DropdownMenuRadioGroup>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem onClick={() => setLogoutOpen(true)} className="text-destructive focus:text-destructive">
                      <LogOut className="size-4 text-destructive" />
                      {t("sidebar.logout")}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            ) : (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <SidebarMenuButton tooltip={t("sidebar.more")}>
                    <MoreHorizontal />
                  </SidebarMenuButton>
                </DropdownMenuTrigger>
                <DropdownMenuContent side="right" align="end">
                  <DropdownMenuRadioGroup value={locale} onValueChange={(v) => setLocale(v as Locale)}>
                    <DropdownMenuRadioItem value="zh">{t("lang.zh")}</DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="en">{t("lang.en")}</DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuRadioGroup value={theme ?? "system"} onValueChange={setTheme}>
                    <DropdownMenuRadioItem value="system">
                      <Monitor className="size-4" />
                      {t("sidebar.themeSystem")}
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="light">
                      <Sun className="size-4" />
                      {t("sidebar.themeLight")}
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="dark">
                      <Moon className="size-4" />
                      {t("sidebar.themeDark")}
                    </DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => setLogoutOpen(true)} className="text-destructive focus:text-destructive">
                    <LogOut className="size-4 text-destructive" />
                    {t("sidebar.logout")}
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
