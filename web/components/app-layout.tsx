"use client"

import { usePathname, useRouter } from "next/navigation"
import { useEffect } from "react"
import { SidebarProvider, SidebarInset } from "@/components/ui/sidebar"
import { AppSidebar } from "@/components/app-sidebar"
import { Toaster } from "@/components/ui/sonner"
import { getToken } from "@/lib/auth"
import { LanguageProvider } from "@/contexts/language-context"
import { Locale } from "@/lib/i18n"
import { useFeaturesStore } from "@/store/features"
import { CommandPalette } from "@/components/command-palette"

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
  const router = useRouter()
  const isLoginPage = pathname === "/login"
  const isPublicPage = pathname === "/auth/feishu/callback"
  const loadFeatures = useFeaturesStore((s) => s.load)

  useEffect(() => {
    if (!isLoginPage && !isPublicPage && !getToken()) {
      router.replace("/login")
    }
  }, [isLoginPage, isPublicPage, router])

  useEffect(() => {
    loadFeatures()
  }, [loadFeatures])

  const layout = (
    <LanguageProvider initialLocale={initialLocale}>
      {children}
      <Toaster position="top-center" />
    </LanguageProvider>
  )

  if (isLoginPage || isPublicPage) {
    return layout
  }

  return (
    <LanguageProvider initialLocale={initialLocale}>
      <SidebarProvider defaultOpen={defaultSidebarOpen}>
        <AppSidebar />
        <SidebarInset className="overflow-x-auto overflow-y-auto overscroll-y-none">
          <div className="flex min-h-full flex-col gap-4 p-4 min-w-[720px]">
            {children}
          </div>
        </SidebarInset>
        <Toaster position="top-center" />
        <CommandPalette />
      </SidebarProvider>
    </LanguageProvider>
  )
}
