"use client"

import { createContext, useContext, useEffect, useState, ReactNode } from "react"
import { getCurrentUser } from "@/lib/api/auth"

interface DocContextValue {
  accessToken: string | null
  baseUrl: string
}

const DocContext = createContext<DocContextValue>({ accessToken: null, baseUrl: "" })

export function DocProvider({ children }: { children: ReactNode }) {
  const [value, setValue] = useState<DocContextValue>({ accessToken: null, baseUrl: "" })

  useEffect(() => {
    let cancelled = false
    getCurrentUser()
      .then((user) => {
        if (cancelled) return
        setValue({
          baseUrl: window.location.origin,
          accessToken: user?.accessToken ?? null,
        })
      })
      .catch(() => {
        if (cancelled) return
        setValue({ baseUrl: window.location.origin, accessToken: null })
      })
    return () => {
      cancelled = true
    }
  }, [])

  return <DocContext.Provider value={value}>{children}</DocContext.Provider>
}

export function useDocContext() {
  return useContext(DocContext)
}
