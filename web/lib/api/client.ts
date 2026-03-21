import { API_BASE_URL } from "./config"
import { getToken, clearAuth } from "@/lib/auth"

export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = getToken()
  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string>),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  }
  const res = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  if (res.status === 401) {
    clearAuth()
    window.location.href = "/login"
  }
  return res
}
