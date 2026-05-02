export function getToken(): string | null {
  if (typeof document === "undefined") return null
  const value = `; ${document.cookie}`
  const parts = value.split(`; auth_token=`)
  if (parts.length === 2) return parts.pop()!.split(";").shift() || null
  return null
}

export function setAuth(token: string, userId: string, username: string, role: string) {
  const maxAge = 7 * 24 * 3600
  document.cookie = `auth_token=${token}; path=/; max-age=${maxAge}; SameSite=Lax`
  localStorage.setItem("auth_user_id", userId)
  localStorage.setItem("auth_username", username)
  localStorage.setItem("auth_role", role)
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
  if (typeof localStorage === "undefined") return null
  return localStorage.getItem("auth_user_id")
}

export function getUsername(): string | null {
  if (typeof localStorage === "undefined") return null
  return localStorage.getItem("auth_username")
}

export function getRole(): string | null {
  if (typeof localStorage === "undefined") return null
  return localStorage.getItem("auth_role")
}

export function isAdmin(): boolean {
  return getRole() === "ADMIN"
}
