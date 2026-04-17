"use client"

import React, { createContext, useContext, useEffect } from "react"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import { useNamespaceStore } from "@/store/namespace"

interface NamespaceContextType {
  namespaces: { id: string; name: string }[]
  selectedNamespace: string
  setSelectedNamespace: (ns: string) => void
  loadNamespaces: () => Promise<void>
}

const NamespaceContext = createContext<NamespaceContextType>({
  namespaces: [],
  selectedNamespace: "",
  setSelectedNamespace: () => {},
  loadNamespaces: async () => {},
})

/**
 * Provides namespace state and syncs the `namespace` URL param with the global store.
 * URL takes priority: if URL has a namespace that differs from the store, the store is updated.
 * If the URL has no namespace but the store does, the namespace is added to the URL.
 *
 * Must be rendered inside a Suspense boundary (uses useSearchParams).
 */
export function NamespaceParamProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const namespaces = useNamespaceStore((s) => s.namespaces)
  const selectedNamespace = useNamespaceStore((s) => s.selectedNamespace)
  const setSelectedNamespace = useNamespaceStore((s) => s.setSelectedNamespace)
  const loadNamespaces = useNamespaceStore((s) => s.load)

  const urlNamespace = searchParams.get("namespace") ?? ""

  useEffect(() => {
    if (urlNamespace && urlNamespace !== selectedNamespace) {
      setSelectedNamespace(urlNamespace)
    } else if (!urlNamespace && selectedNamespace) {
      const params = new URLSearchParams(searchParams.toString())
      params.set("namespace", selectedNamespace)
      router.replace(`${pathname}?${params.toString()}`)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlNamespace, selectedNamespace])

  return (
    <NamespaceContext.Provider value={{ namespaces, selectedNamespace, setSelectedNamespace, loadNamespaces }}>
      {children}
    </NamespaceContext.Provider>
  )
}

export function useNamespace() {
  return useContext(NamespaceContext)
}
