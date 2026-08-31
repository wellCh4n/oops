// Claim reading for the auth JWT, shared by the browser helpers (lib/auth.ts) and
// the Next.js middleware (proxy.ts, edge runtime) — hence no `document`/`window`
// here. The signature is never checked: these claims only drive what the UI shows,
// and every request is authorized again by the backend.

export interface JwtClaims {
  sub: string
  userId: string
  role: string
  exp?: number
}

export function decodeJwt(token: string): JwtClaims | null {
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

export function isAdminToken(token: string | null | undefined): boolean {
  return !!token && decodeJwt(token)?.role === "ADMIN"
}
