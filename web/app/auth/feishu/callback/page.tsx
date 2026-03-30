"use client"

import { useEffect } from "react"
import { useSearchParams } from "next/navigation"
import { setAuth } from "@/lib/auth"
import { getCurrentUser } from "@/lib/api/auth"
import { API_BASE_URL } from "@/lib/api/config"

export default function FeishuCallbackPage() {
  const searchParams = useSearchParams()

  useEffect(() => {
    const code = searchParams.get("code")

    if (!code) {
      window.location.href = "/login"
      return
    }

    fetch(`${API_BASE_URL}/api/auth/feishu/callback?code=${code}`, {
      method: "POST",
    })
      .then((res) => res.json())
      .then((data) => {
        if (data.success && data.data) {
          const token = data.data
          document.cookie = `auth_token=${token}; path=/; max-age=${7 * 24 * 3600}; SameSite=Lax`
          return getCurrentUser().then((user) => ({ token, user }))
        } else {
          throw new Error(data.message || "登录失败")
        }
      })
      .then(({ token, user }) => {
        if (user) {
          setAuth(token, user.username, user.role)
        }
        window.location.href = "/"
      })
      .catch((err) => {
        alert("登录失败: " + err.message)
        window.location.href = "/login"
      })
  }, [searchParams])

  return (
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh" }}>
      <p>处理中...</p>
    </div>
  )
}