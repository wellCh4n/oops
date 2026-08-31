import { NextResponse } from "next/server"
import type { NextRequest } from "next/server"
import { AUTH_TOKEN_COOKIE } from "@/lib/auth-keys"
import { isAdminToken } from "@/lib/jwt"
import { isAdminOnlyPath } from "@/lib/nav-config"

export function proxy(request: NextRequest) {
  const token = request.cookies.get(AUTH_TOKEN_COOKIE)?.value
  const { pathname } = request.nextUrl

  if (pathname === "/login") {
    if (token) {
      return NextResponse.redirect(new URL("/", request.url))
    }
    return NextResponse.next()
  }

  if (!token && !pathname.startsWith("/auth/feishu/callback") && !pathname.startsWith("/api/auth/external")) {
    return NextResponse.redirect(new URL("/login", request.url))
  }

  // Admin-only sections are turned away here rather than in the layout, so a
  // non-admin never loads the page at all — no blank shell, no bounce, and no
  // history entry pointing at a page they cannot open. The backend still
  // authorizes every call; this only keeps the UI honest.
  if (token && isAdminOnlyPath(pathname) && !isAdminToken(token)) {
    return NextResponse.redirect(new URL("/apps", request.url))
  }

  return NextResponse.next()
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon.png).*)"],
}
