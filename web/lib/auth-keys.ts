// Shared auth storage key names. Centralized here so the Next.js middleware
// (proxy.ts, edge runtime) and the browser-side auth helpers (lib/auth.ts) stay
// in sync without duplicating magic strings.

export const AUTH_TOKEN_COOKIE = "auth_token"
export const AUTH_USER_ID_KEY = "auth_user_id"
export const AUTH_USERNAME_KEY = "auth_username"
export const AUTH_ROLE_KEY = "auth_role"
