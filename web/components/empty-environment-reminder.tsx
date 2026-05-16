"use client"

import { useEffect } from "react"
import { usePathname, useRouter } from "next/navigation"
import { toast } from "sonner"
import { fetchEnvironments } from "@/lib/api/environments"
import { useLanguage } from "@/contexts/language-context"
import { getToken, isAdmin } from "@/lib/auth"

let checked = false

export function EmptyEnvironmentReminder() {
  const pathname = usePathname()
  const router = useRouter()
  const { t } = useLanguage()

  useEffect(() => {
    if (!getToken()) return
    if (pathname.startsWith("/settings/environments")) return
    if (checked) return
    checked = true

    const admin = isAdmin()
    let cancelled = false
    fetchEnvironments()
      .then((res) => {
        if (cancelled) return
        if (!res.success || (res.data && res.data.length > 0)) return
        if (admin) {
          toast.warning(t("env.emptyWarning"), {
            duration: 10000,
            action: {
              label: t("env.goConfigure"),
              onClick: () => router.push("/settings/environments"),
            },
          })
        } else {
          toast.warning(t("env.emptyWarningUser"), { duration: 10000 })
        }
      })
      .catch(() => {})

    return () => {
      cancelled = true
    }
  }, [pathname, router, t])

  return null
}
