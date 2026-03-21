import { API_BASE_URL } from "./config"
import { setAuth } from "@/lib/auth"

export interface LoginResult {
  token: string
  username: string
  role: string
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
