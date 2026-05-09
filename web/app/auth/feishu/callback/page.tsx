"use client"

import { useEffect, useRef } from "react"
import { useSearchParams } from "next/navigation"
import { Loader2 } from "lucide-react"
import { setAuth } from "@/lib/auth"
import { getCurrentUser, feishuCallback } from "@/lib/api/auth"
import { useLanguage } from "@/contexts/language-context"

export default function FeishuCallbackPage() {
  const searchParams = useSearchParams()
  const { t } = useLanguage()
  const exchangedRef = useRef(false)

  useEffect(() => {
    const code = searchParams.get("code")

    if (!code) {
      window.location.href = "/login"
      return
    }

    if (exchangedRef.current) {
      return
    }
    exchangedRef.current = true

    feishuCallback(code)
      .then((token) => {
        setAuth(token)
        return getCurrentUser()
      })
      .then((user) => {
        if (!user) {
          throw new Error(t("login.error"))
        }
        window.location.href = "/"
      })
      .catch((err: Error) => {
        alert(t("login.callback.error") + err.message)
        window.location.href = "/login"
      })
  }, [searchParams, t])

  return (
    <div className="flex h-screen flex-col items-center justify-center gap-4 bg-background">
      <Loader2 className="h-10 w-10 animate-spin text-primary" />
      <p className="text-sm text-muted-foreground">{t("login.callback.processing")}</p>
    </div>
  )
}
