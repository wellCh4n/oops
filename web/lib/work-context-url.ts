import { ALL_NAMESPACES, type AppRef } from "@/store/work-context"

/**
 * Which pieces of the work context each route understands, and how to stamp
 * them onto a link.
 *
 * Every navigation into a context-aware page goes through `workContextHref`, so
 * the destination arrives with a complete URL instead of guessing a default
 * after mount. That is what lets apps → pipelines → IDEs keep showing the same
 * app and environment.
 */

export type WorkContextParam = "namespace" | "app" | "env"

/** The URL query key a context param is spelled as ("env" travels as "environment"). */
export function urlKeyFor(param: WorkContextParam): string {
  return param === "env" ? "environment" : param
}

const ROUTE_PARAMS: { match: (pathname: string) => boolean; params: WorkContextParam[] }[] = [
  { match: (pathname) => pathname === "/apps", params: ["namespace"] },
  { match: (pathname) => pathname === "/pipelines", params: ["namespace", "app", "env"] },
  { match: (pathname) => pathname === "/ides", params: ["namespace", "app", "env"] },
  // Application detail pages carry namespace and app in the path already.
  { match: (pathname) => pathname.startsWith("/apps/"), params: ["env"] },
]

export function contextParamsFor(pathname: string): WorkContextParam[] {
  return ROUTE_PARAMS.find((route) => route.match(pathname))?.params ?? []
}

export interface WorkContextValue {
  namespace: string
  app: AppRef | null
  env: string
}

/**
 * Resolve the context values a route should actually carry. An app is only
 * included when it lives inside the current scope, and the environment is only
 * included alongside an app — an env without an app addresses nothing.
 */
export function effectiveContextParams(
  params: WorkContextParam[],
  { namespace, app, env }: WorkContextValue
): Record<string, string> {
  const result: Record<string, string> = {}
  if (params.includes("namespace") && namespace) {
    result.namespace = namespace
  }

  const appInScope = app && (namespace === ALL_NAMESPACES || app.namespace === namespace)
  if (params.includes("app")) {
    if (appInScope) result.app = app.name
  }
  if (params.includes("env") && env) {
    // On detail pages the app comes from the path, so env stands on its own.
    if (!params.includes("app") || appInScope) result.environment = env
  }
  return result
}

/** Build an href for `url`, stamped with whichever context params it accepts. */
export function workContextHref(url: string, context: WorkContextValue): string {
  const values = effectiveContextParams(contextParamsFor(url), context)
  const query = new URLSearchParams(values).toString()
  return query ? `${url}?${query}` : url
}
