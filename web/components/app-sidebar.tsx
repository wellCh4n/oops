"use client"

import Link from "next/link"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { ChevronRight, LogOut, Monitor, Moon, Sun, PanelLeftClose, PanelLeftOpen, MoreHorizontal, Keyboard, UserCog } from "lucide-react"

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
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
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
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { navConfig } from "@/lib/nav-config"
import { useFeaturesStore } from "@/store/features"
import { useNamespaceStore } from "@/store/namespace"
import { usePathname, useSearchParams } from "next/navigation"
import { Suspense, useState, useEffect } from "react"
import { clearAuth, isAdmin } from "@/lib/auth"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"
import { useTheme } from "next-themes"
import { useLanguage } from "@/contexts/language-context"
import { localeLabels, Locale } from "@/lib/i18n"
import { useSidebarNavStore } from "@/store/sidebar"

export function AppSidebar({ onOpenCommandPalette }: { onOpenCommandPalette: () => void }) {
  return (
    <Suspense fallback={null}>
      <AppSidebarContent onOpenCommandPalette={onOpenCommandPalette} />
    </Suspense>
  )
}

function AppSidebarContent({ onOpenCommandPalette }: { onOpenCommandPalette: () => void }) {
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const router = useRouter()
  const { open: sidebarOpen, toggleSidebar } = useSidebar()
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [isAdminUser, setIsAdminUser] = useState(false)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const { theme, setTheme } = useTheme()
  const { locale, setLocale, t } = useLanguage()
  const ideEnabled = useFeaturesStore((s) => s.features.ide)
  const objectStorageEnabled = useFeaturesStore((s) => s.features.objectStorage)
  const selectedNamespace = useNamespaceStore((s) => s.selectedNamespace)
  const expandedGroups = useSidebarNavStore((s) => s.expandedGroups)
  const setGroupExpanded = useSidebarNavStore((s) => s.setGroupExpanded)

  useEffect(() => {
    getCurrentUser().then(setCurrentUser)
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIsAdminUser(isAdmin())
  }, [])

  function handleLogout() {
    clearAuth()
    router.replace("/login")
  }

  function confirmLogout() {
    setLogoutOpen(false)
    handleLogout()
  }

  const locales = Object.keys(localeLabels) as Locale[]

  function isGroupExpanded(groupTitle: string) {
    return expandedGroups.includes(groupTitle)
  }

  return (
    <>
    <AlertDialog open={logoutOpen} onOpenChange={setLogoutOpen}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t("sidebar.logoutConfirm")}</AlertDialogTitle>
          <AlertDialogDescription>{t("sidebar.logoutDescription")}</AlertDialogDescription>
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
            <div className="flex items-center p-1">
              <Link href="/" className="flex items-center gap-2 min-w-0">
                <div className={`relative aspect-square overflow-hidden shrink-0 ${sidebarOpen ? "size-12 rounded-lg" : "size-6 rounded-md"}`}>
                  <Image src="/icon.png" alt="Oops" fill sizes="48px" priority className="object-cover dark:[filter:url(#white-stroke)]" />
                </div>
                {sidebarOpen && (
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
      <SidebarContent className="gap-0">
        {navConfig.map((group) => {
          if (group.adminOnly && !isAdminUser) return null
          const filteredGroups = group.items.filter((item) => {
            if (item.adminOnly && !isAdminUser) return false
            if (item.url === "/ides" && !ideEnabled) return false
            if (item.url === "/assets" && !objectStorageEnabled) return false
            return true
          })
          if (filteredGroups.length === 0) return null
          const groupExpanded = isGroupExpanded(group.title)
          const groupItems = (
            <SidebarGroupContent>
              <SidebarMenu>
                {filteredGroups.map((item) => {
                  const href = pathname === item.url
                    ? (searchParams.toString() ? `${pathname}?${searchParams.toString()}` : pathname)
                    : selectedNamespace && (item.url === "/ides" || item.url === "/pipelines")
                      ? `${item.url}?namespace=${selectedNamespace}`
                      : item.url

                  return (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      asChild
                      isActive={item.match ? item.match(pathname) : pathname === item.url || pathname.startsWith(item.url + "/")}
                      tooltip={t(item.title)}
                    >
                      <Link href={href}>
                        <item.icon />
                        <span>{t(item.title)}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                  )
                })}
              </SidebarMenu>
            </SidebarGroupContent>
          )

          return (
          <Collapsible
            key={group.title}
            open={sidebarOpen ? groupExpanded : true}
            onOpenChange={(expanded) => setGroupExpanded(group.title, expanded)}
          >
            <SidebarGroup className="py-1 px-2">
              {sidebarOpen && (
                <SidebarGroupLabel
                  asChild
                  className="w-full cursor-pointer justify-between pr-1 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
                >
                  <CollapsibleTrigger
                    aria-label={`${groupExpanded ? t("sidebar.collapseGroup") : t("sidebar.expandGroup")}: ${t(group.title)}`}
                  >
                    <span className="truncate">{t(group.title)}</span>
                    <ChevronRight className={`ml-auto transition-transform ${groupExpanded ? "rotate-90" : ""}`} />
                  </CollapsibleTrigger>
                </SidebarGroupLabel>
              )}
              {sidebarOpen ? (
                <CollapsibleContent>
                  {groupItems}
                </CollapsibleContent>
              ) : (
                groupItems
              )}
            </SidebarGroup>
          </Collapsible>
          )
        })}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          {sidebarOpen && (
            <SidebarMenuItem>
              <SidebarMenuButton
                onClick={onOpenCommandPalette}
                tooltip={t("cmd.hint")}
              >
                <Keyboard className="size-4" />
                <span>{t("cmd.hint")}</span>
                <kbd className="bg-sidebar-border px-1.5 py-0.5 rounded-sm text-[10px] font-mono ml-auto">/</kbd>
              </SidebarMenuButton>
            </SidebarMenuItem>
          )}
          <SidebarMenuItem>
            <SidebarMenuButton
              onClick={toggleSidebar}
              tooltip={sidebarOpen ? t("sidebar.collapse") : t("sidebar.expand")}
            >
              {sidebarOpen ? <PanelLeftClose /> : <PanelLeftOpen />}
              <span>{sidebarOpen ? t("sidebar.collapse") : t("sidebar.expand")}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
          <SidebarMenuItem>
            {sidebarOpen ? (
              <div className="flex items-center justify-between gap-1 pr-1">
                <Link
                  href="/settings/profile"
                  className="flex flex-col min-w-0 flex-1 text-left rounded-md px-2 py-1 cursor-pointer hover:bg-sidebar-accent hover:text-sidebar-accent-foreground transition-colors"
                  title={t("sidebar.profile")}
                >
                  <span className="text-sm text-muted-foreground truncate">{currentUser?.username}</span>
                  {currentUser?.email && (
                    <span className="text-xs text-muted-foreground/70 truncate">{currentUser.email}</span>
                  )}
                </Link>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="size-7 ml-2 shrink-0 text-muted-foreground hover:text-foreground" aria-label={t("sidebar.more")}>
                      <MoreHorizontal className="size-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" side="top">
                    <DropdownMenuRadioGroup value={locale} onValueChange={(v) => setLocale(v as Locale)}>
                      {locales.map((itemLocale) => (
                        <DropdownMenuRadioItem key={itemLocale} value={itemLocale}>
                          {localeLabels[itemLocale]}
                        </DropdownMenuRadioItem>
                      ))}
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
                    <DropdownMenuItem asChild>
                      <Link href="/settings/profile">
                        <UserCog className="size-4" />
                        {t("sidebar.profile")}
                      </Link>
                    </DropdownMenuItem>
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
                    {locales.map((itemLocale) => (
                      <DropdownMenuRadioItem key={itemLocale} value={itemLocale}>
                        {localeLabels[itemLocale]}
                      </DropdownMenuRadioItem>
                    ))}
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
                  <DropdownMenuItem asChild>
                    <Link href="/settings/profile">
                      <UserCog className="size-4" />
                      {t("sidebar.profile")}
                    </Link>
                  </DropdownMenuItem>
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
