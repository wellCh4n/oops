"use client"

import { useEffect } from "react"
import { useSearchParams } from "next/navigation"
import { setAuth } from "@/lib/auth"
import { getCurrentUser, feishuCallback } from "@/lib/api/auth"
import { useLanguage } from "@/contexts/language-context"

export default function FeishuCallbackPage() {
  const searchParams = useSearchParams()
  const { t } = useLanguage()

  useEffect(() => {
    const code = searchParams.get("code")

    if (!code) {
      window.location.href = "/login"
      return
    }

    feishuCallback(code)
      .then((token) => {
        document.cookie = `auth_token=${token}; path=/; max-age=${7 * 24 * 3600}; SameSite=Lax`
        return getCurrentUser().then((user) => ({ token, user }))
      })
      .then(({ token, user }) => {
        if (user) {
          setAuth(token, user.id, user.username, user.role)
        }
        window.location.href = "/"
      })
      .catch((err: Error) => {
        alert(t("login.callback.error") + err.message)
        window.location.href = "/login"
      })
  }, [searchParams, t])

  return (
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh" }}>
      <p>{t("login.callback.processing")}</p>
    </div>
  )
}
