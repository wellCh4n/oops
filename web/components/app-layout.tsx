"use client"

import { usePathname, useRouter } from "next/navigation"
import { useEffect } from "react"
import { SidebarProvider, SidebarInset } from "@/components/ui/sidebar"
import { AppSidebar } from "@/components/app-sidebar"
import { HeaderTitle } from "@/components/header-title"
import { Toaster } from "@/components/ui/sonner"
import { getToken } from "@/lib/auth"

export function AppLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const isLoginPage = pathname === "/login"

  useEffect(() => {
    if (!isLoginPage && !getToken()) {
      router.replace("/login")
    }
  }, [isLoginPage, router])

  if (isLoginPage) {
    return (
      <>
        {children}
        <Toaster position="top-center" />
      </>
    )
  }

  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <HeaderTitle />
        <div className="flex flex-1 min-h-0 flex-col gap-4 p-4 overflow-y-auto">
          {children}
        </div>
      </SidebarInset>
      <Toaster position="top-center" />
    </SidebarProvider>
  )
}
