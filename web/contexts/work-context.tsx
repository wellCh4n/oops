"use client"

import { useCallback, useEffect, useMemo } from "react"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { ALL_NAMESPACES, useWorkContextStore, type AppRef } from "@/store/work-context"
import { contextParamsFor, effectiveContextParams } from "@/lib/work-context-url"

/**
 * Reads the work context for the current route and gives back the setters that
 * change it.
 *
 * Rules, in order of importance:
 *
 * 1. While on a page the URL is the truth; the store only fills in params the
 *    URL does not carry (a direct visit, or a bookmark from before).
 * 2. Nothing navigates on mount. Missing params are resolved by *reading* the
 *    store, not by rewriting the URL — that used to cause the mount-time
 *    `router.replace` flicker and made effect ordering matter.
 * 3. Only `select*` writes, and it writes the store and the URL together, so
 *    the two can never disagree.
 *
 * Callers must be inside a Suspense boundary (uses `useSearchParams`).
 */
export function useWorkContext() {
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const router = useRouter()

  const namespaces = useWorkContextStore((state) => state.namespaces)
  const loadNamespaces = useWorkContextStore((state) => state.load)
  const storedNamespace = useWorkContextStore((state) => state.namespace)
  const storedApp = useWorkContextStore((state) => state.app)
  const storedEnv = useWorkContextStore((state) => state.env)
  const setContext = useWorkContextStore((state) => state.setContext)

  const accepted = useMemo(() => contextParamsFor(pathname), [pathname])

  const urlNamespace = accepted.includes("namespace") ? searchParams.get("namespace") : null
  const urlApp = accepted.includes("app") ? searchParams.get("app") : null
  const urlEnv = accepted.includes("env") ? searchParams.get("env") : null

  // URL wins; the store fills the gaps.
  const namespace = urlNamespace ?? storedNamespace
  const appName = urlApp ?? (storedApp && appInScope(storedApp, namespace) ? storedApp.name : "")
  const env = urlEnv ?? storedEnv

  // Best concrete namespace for `appName`. Under the "all" scope this is only
  // known once the page has loaded its application list and called `resolveApp`.
  const appNamespace = storedApp?.name === appName && storedApp.namespace
    ? storedApp.namespace
    : namespace === ALL_NAMESPACES
      ? ""
      : namespace

  // Adopt whatever the URL states, so links built later reflect where the user
  // actually is. Store-only — this never navigates, so it cannot loop.
  useEffect(() => {
    const patch: Parameters<typeof setContext>[0] = {}
    if (urlNamespace && urlNamespace !== storedNamespace) {
      patch.namespace = urlNamespace
    }
    if (urlApp !== null && urlApp !== (storedApp?.name ?? "")) {
      const scope = patch.namespace ?? storedNamespace
      patch.app = urlApp
        ? { namespace: scope === ALL_NAMESPACES ? "" : scope, name: urlApp }
        : null
    }
    if (urlEnv !== null && urlEnv !== storedEnv) {
      patch.env = urlEnv
    }
    if (Object.keys(patch).length > 0) setContext(patch)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlNamespace, urlApp, urlEnv])

  /** Write a new context to the store and mirror it into the URL in one step. */
  const commit = useCallback(
    (next: { namespace: string; app: AppRef | null; env: string }, extraParams?: Record<string, string>) => {
      setContext(next)

      const params = new URLSearchParams(searchParams.toString())
      const values = effectiveContextParams(accepted, next)
      accepted.forEach((param) => {
        const value = values[param]
        if (value) params.set(param, value)
        else params.delete(param)
      })
      Object.entries(extraParams ?? {}).forEach(([key, value]) => {
        if (value) params.set(key, value)
        else params.delete(key)
      })
      const query = params.toString()
      router.replace(query ? `${pathname}?${query}` : pathname)
    },
    [accepted, pathname, router, searchParams, setContext]
  )

  // Switching scope drops the app and env: an app from the old namespace is
  // almost never what you want to keep looking at, and silently keeping it is
  // exactly the kind of stale state this rewrite removes.
  const selectNamespace = useCallback(
    (nextNamespace: string, extraParams?: Record<string, string>) => {
      commit({ namespace: nextNamespace, app: null, env: "" }, extraParams)
    },
    [commit]
  )

  // The environment belongs to the app, so changing app resets it.
  const selectApp = useCallback(
    (nextApp: AppRef | null, extraParams?: Record<string, string>) => {
      commit({ namespace, app: nextApp, env: "" }, extraParams)
    },
    [commit, namespace]
  )

  const selectEnv = useCallback(
    (nextEnv: string, extraParams?: Record<string, string>) => {
      commit({ namespace, app: storedApp, env: nextEnv }, extraParams)
    },
    [commit, namespace, storedApp]
  )

  /**
   * Fill in details (real namespace, description, owner) for the app already in
   * context once the page has loaded its list. Store-only: the selection itself
   * is unchanged, so this must not navigate.
   */
  const resolveApp = useCallback(
    (resolved: AppRef) => {
      if (resolved.name !== appName) return
      if (storedApp?.name === resolved.name && storedApp.namespace === resolved.namespace) return
      setContext({ app: resolved })
    },
    [appName, storedApp, setContext]
  )

  return {
    namespaces,
    loadNamespaces,
    namespace,
    appName,
    appNamespace,
    env,
    selectNamespace,
    selectApp,
    selectEnv,
    resolveApp,
  }
}

function appInScope(app: AppRef, namespace: string) {
  return namespace === ALL_NAMESPACES || app.namespace === "" || app.namespace === namespace
}
