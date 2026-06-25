import { NextResponse } from "next/server"
import type { NextRequest } from "next/server"
import { AUTH_TOKEN_COOKIE } from "@/lib/auth-keys"

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

  return NextResponse.next()
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon.png).*)"],
}
