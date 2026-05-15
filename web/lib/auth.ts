interface JwtClaims {
  sub: string
  userId: string
  role: string
  exp?: number
}

function decodeJwt(token: string): JwtClaims | null {
  try {
    const payload = token.split(".")[1]
    if (!payload) return null
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/")
    const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4)
    const json = typeof atob === "function"
      ? atob(padded)
      : Buffer.from(padded, "base64").toString("utf-8")
    return JSON.parse(json) as JwtClaims
  } catch {
    return null
  }
}

export function getToken(): string | null {
  if (typeof document === "undefined") return null
  const value = `; ${document.cookie}`
  const parts = value.split(`; auth_token=`)
  if (parts.length === 2) return parts.pop()!.split(";").shift() || null
  return null
}

function getClaims(): JwtClaims | null {
  const token = getToken()
  return token ? decodeJwt(token) : null
}

export function setAuth(token: string) {
  const maxAge = 7 * 24 * 3600
  document.cookie = `auth_token=${token}; path=/; max-age=${maxAge}; SameSite=Lax`
}

export function clearAuth() {
  if (typeof document !== "undefined") {
    document.cookie = "auth_token=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT"
    document.cookie = "auth_token=; path=/; max-age=0; SameSite=Lax"
  }
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("auth_user_id")
    localStorage.removeItem("auth_username")
    localStorage.removeItem("auth_role")
  }
}

export function handleAuthFailure() {
  clearAuth()
  if (typeof window !== "undefined" && window.location.pathname !== "/login") {
    window.location.replace("/login")
  }
}

export function getUserId(): string | null {
  return getClaims()?.userId ?? null
}

export function isAdmin(): boolean {
  return getClaims()?.role === "ADMIN"
}
