import { API_BASE_URL } from "./config"
import { setAuth, getToken, handleAuthFailure } from "@/lib/auth"
import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface LoginResult {
  token: string
  id: string
  username: string
  role: string
}

export interface CurrentUser {
  id: string
  username: string
  email: string | null
  role: string
  accessToken: string | null
}

export async function getFeishuLoginUrl(): Promise<string> {
  const res = await apiFetch("/api/auth/external/feishu/redirect")
  const data = await res.json() as ApiResponse<string>
  if (!data.success || !data.data) {
    throw new Error(data.message || "Failed to get Feishu login URL")
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
    if (res.status === 401) {
      handleAuthFailure()
      return null
    }
    const data = await res.json() as ApiResponse<CurrentUser>
    if (!data.success || !data.data) {
      handleAuthFailure()
      return null
    }
    return data.data
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
  const data = await res.json() as ApiResponse<LoginResult>
  if (!data.success) {
    throw new Error(data.message || "Login failed")
  }
  setAuth(data.data.token)
  return data.data
}

export async function feishuCallback(code: string): Promise<string> {
  const res = await fetch(`${API_BASE_URL}/api/auth/external/feishu/callback?code=${code}`, {
    method: "POST",
  })
  const data = await res.json() as ApiResponse<string>
  if (!data.success || !data.data) {
    throw new Error(data.message || "Login failed")
  }
  return data.data
}
