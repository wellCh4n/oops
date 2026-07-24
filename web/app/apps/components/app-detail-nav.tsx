"use client"

import { Suspense, useRef, useState } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { Pencil, Rocket, Activity, ChevronDown, type LucideIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { useLanguage } from "@/contexts/language-context"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"

type Section = "edit" | "publish" | "status"

interface AppDetailNavProps {
  namespace: string
  name: string
  active: Section
}

// Edit sub-tabs surfaced in the hover dropdown. Order mirrors the tab bar in
// application-form.tsx; "danger-zone" is intentionally excluded. `tab` is the
// `?tab=` value that application-form reads to select the active editor tab.
const EDIT_TABS: { tab: string; labelKey: string }[] = [
  { tab: "app-info", labelKey: "apps.tab.appInfo" },
  { tab: "build-config", labelKey: "apps.tab.buildConfig" },
  { tab: "service-info", labelKey: "apps.tab.serviceConfig" },
  { tab: "runtime-spec", labelKey: "apps.tab.runtimeSpec" },
  { tab: "config-info", labelKey: "apps.tab.configMgmt" },
  { tab: "expert-config", labelKey: "apps.tab.expertConfig" },
]

// Shared segmented nav rendered in the ContentPage header `actions` slot on
// every application detail page, so Edit / Publish / Status are one click apart
// without bouncing back to the app list. Wrapped in its own Suspense boundary
// because it reads `?env=` via useSearchParams — callers need not provide one
// themselves.
export function AppDetailNav(props: AppDetailNavProps) {
  return (
    <Suspense fallback={null}>
      <AppDetailNavInner {...props} />
    </Suspense>
  )
}

const itemClass = (isActive: boolean) =>
  cn(
    "flex h-7 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium transition-colors cursor-pointer",
    isActive
      ? "bg-sidebar-accent text-sidebar-accent-foreground"
      : "text-sidebar-foreground/60 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground"
  )

function AppDetailNavInner({ namespace, name, active }: AppDetailNavProps) {
  const { t } = useLanguage()
  const searchParams = useSearchParams()
  const env = searchParams.get("env")

  const base = `/apps/${namespace}/${name}`
  const envParam = env ? `env=${encodeURIComponent(env)}` : ""
  const withEnv = (query: string) => {
    const parts = [query, envParam].filter(Boolean)
    return parts.length ? `?${parts.join("&")}` : ""
  }

  // Hover-driven open state. A short close delay bridges the gap between the
  // trigger and the (portaled) menu so the pointer can travel without it snapping shut.
  const [editOpen, setEditOpen] = useState(false)
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const openEditMenu = () => {
    if (closeTimer.current) clearTimeout(closeTimer.current)
    setEditOpen(true)
  }
  const scheduleCloseEditMenu = () => {
    if (closeTimer.current) clearTimeout(closeTimer.current)
    closeTimer.current = setTimeout(() => setEditOpen(false), 120)
  }

  const plainItems: { key: Section; href: string; icon: LucideIcon; label: string }[] = [
    { key: "publish", href: `${base}/publish${withEnv("")}`, icon: Rocket, label: t("apps.col.publish") },
    { key: "status", href: `${base}/status${withEnv("")}`, icon: Activity, label: t("apps.col.status") },
  ]

  return (
    <nav className="flex items-center gap-1">
      {/* Edit: click goes to the default (basic info) tab; hover reveals the sub-tabs. */}
      <DropdownMenu open={editOpen} onOpenChange={setEditOpen} modal={false}>
        <div onMouseEnter={openEditMenu} onMouseLeave={scheduleCloseEditMenu}>
          <DropdownMenuTrigger asChild>
            <Link
              href={`${base}${withEnv("")}`}
              aria-current={active === "edit" ? "page" : undefined}
              onClick={() => setEditOpen(false)}
              className={itemClass(active === "edit")}
            >
              <Pencil className="size-3.5" />
              <span className="hidden sm:inline">{t("apps.col.edit")}</span>
              <ChevronDown className={cn("size-3 opacity-60 transition-transform", editOpen && "rotate-180")} />
            </Link>
          </DropdownMenuTrigger>
        </div>
        <DropdownMenuContent
          align="end"
          sideOffset={4}
          onMouseEnter={openEditMenu}
          onMouseLeave={scheduleCloseEditMenu}
          onCloseAutoFocus={(event) => event.preventDefault()}
          className="min-w-36"
        >
          {EDIT_TABS.map(({ tab, labelKey }) => (
            <DropdownMenuItem key={tab} asChild className="text-xs cursor-pointer">
              <Link href={`${base}${withEnv(`tab=${tab}`)}`}>{t(labelKey)}</Link>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      {plainItems.map(({ key, href, icon: Icon, label }) => (
        <Link key={key} href={href} aria-current={key === active ? "page" : undefined} className={itemClass(key === active)}>
          <Icon className="size-3.5" />
          <span className="hidden sm:inline">{label}</span>
        </Link>
      ))}
    </nav>
  )
}
