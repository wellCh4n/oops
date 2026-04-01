"use client"

import { usePathname, useRouter } from "next/navigation"
import { useEffect } from "react"
import { SidebarProvider, SidebarInset } from "@/components/ui/sidebar"
import { AppSidebar } from "@/components/app-sidebar"
import { Toaster } from "@/components/ui/sonner"
import { getToken } from "@/lib/auth"
import { LanguageProvider } from "@/contexts/language-context"
import { Locale } from "@/lib/i18n"

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

  useEffect(() => {
    if (!isLoginPage && !isPublicPage && !getToken()) {
      router.replace("/login")
    }
  }, [isLoginPage, isPublicPage, router])

  if (isLoginPage) {
    return (
      <>
        {children}
        <Toaster position="top-center" />
      </>
    )
  }

  if (isPublicPage) {
    return (
      <>
        {children}
        <Toaster position="top-center" />
      </>
    )
  }

  return (
    <LanguageProvider initialLocale={initialLocale}>
      <SidebarProvider defaultOpen={defaultSidebarOpen}>
        <AppSidebar />
        <SidebarInset className="overflow-x-auto">
          <div className="flex flex-1 min-h-0 flex-col gap-4 p-4 overflow-y-auto min-w-[720px]">
            {children}
          </div>
        </SidebarInset>
        <Toaster position="top-center" />
      </SidebarProvider>
    </LanguageProvider>
  )
}
