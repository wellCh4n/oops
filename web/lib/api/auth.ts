import { API_BASE_URL } from "./config"
import { setAuth, getToken } from "@/lib/auth"
import { apiFetch } from "./client"

export interface LoginResult {
  token: string
  username: string
  role: string
}

export interface CurrentUser {
  id: string
  username: string
  email: string | null
  role: string
}

export async function getFeishuLoginUrl(): Promise<string> {
  const res = await apiFetch("/api/auth/feishu/redirect")
  const data = await res.json()
  if (!data.success || !data.data) {
    throw new Error(data.message || "获取飞书登录地址失败")
  }
  return data.data
}

export async function getCurrentUser(): Promise<CurrentUser | null> {
  const token = getToken()
  if (!token) return null
  try {
    const res = await fetch(`${API_BASE_URL}/api/users/me`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const data = await res.json()
    return data.success ? data.data : null
  } catch {
    return null
  }
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  })
  const data = await res.json()
  if (!data.success) {
    throw new Error(data.message || "登录失败")
  }
  setAuth(data.data.token, data.data.username, data.data.role)
  return data.data
}
