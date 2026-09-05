"use client"

import { useEffect, useState } from "react"
import { fetchNamespaces } from "@/lib/api/namespaces"

export interface NamespaceOption {
  id: string
  name: string
}

/** The namespace filter's wildcard, understood by every list endpoint. */
export const ALL_NAMESPACES = "all"

/**
 * The namespace list for a filter dropdown. Fetched per page mount and never
 * persisted: which namespace a page shows is decided by its URL alone.
 */
export function useNamespaces() {
  const [namespaces, setNamespaces] = useState<NamespaceOption[]>([])
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetchNamespaces()
      .then((res) => {
        if (cancelled) return
        const list = Array.isArray(res.data) ? res.data : []
        setNamespaces(list.map((namespace) => ({ id: namespace.name, name: namespace.name })))
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { namespaces, loaded }
}
