"use client"

import { usePathname } from "next/navigation"
import { Suspense, useEffect, useState } from "react"
import { SidebarProvider, SidebarInset } from "@/components/ui/sidebar"
import { AppSidebar } from "@/components/app-sidebar"
import { Toaster } from "@/components/ui/sonner"
import { LanguageProvider } from "@/contexts/language-context"
import { Locale } from "@/lib/i18n"
import { useFeaturesStore } from "@/store/features"
import { CommandPalette } from "@/components/command-palette"
import { EmptyEnvironmentReminder } from "@/components/empty-environment-reminder"

export function AppLayout({
  children,
  defaultSidebarOpen,
  initialLocale,
}: {
  children: React.ReactNode
  defaultSidebarOpen?: boolean
  initialLocale: Locale
}) {
  const pathname = usePathname()
  const isLoginPage = pathname === "/login"
  const isPublicPage = pathname === "/auth/feishu/callback"
  const loadFeatures = useFeaturesStore((s) => s.load)
  const [cmdOpen, setCmdOpen] = useState(false)

  // Signed-out and non-admin visitors are turned away by the middleware
  // (proxy.ts) before this layout ever renders, so there is no guard here.

  useEffect(() => {
    loadFeatures()
  }, [loadFeatures])

  const layout = (
    <LanguageProvider initialLocale={initialLocale}>
      {children}
      <Toaster position="top-right" />
    </LanguageProvider>
  )

  if (isLoginPage || isPublicPage) {
    return layout
  }

  return (
    <LanguageProvider initialLocale={initialLocale}>
      <SidebarProvider defaultOpen={defaultSidebarOpen}>
        <Suspense fallback={null}>
          <AppSidebar onOpenCommandPalette={() => setCmdOpen(true)} />
        </Suspense>
        <SidebarInset className="overflow-x-auto overflow-y-auto overscroll-y-none">
          <div className="flex min-h-full flex-col gap-4 p-4 min-w-[720px]">
            {children}
          </div>
        </SidebarInset>
        <Toaster position="top-right" />
        <CommandPalette open={cmdOpen} onOpenChange={setCmdOpen} />
        <EmptyEnvironmentReminder />
      </SidebarProvider>
    </LanguageProvider>
  )
}
