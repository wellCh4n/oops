"use client"

import { useEffect } from "react"
import { useRouter } from "next/navigation"
import { ContentPage } from "@/components/content-page"
import { useNamespaceStore } from "@/store/namespace"
import { applicationsPath } from "@/lib/routes"

export default function NamespacesEntryPage() {
  const router = useRouter()
  const namespaces = useNamespaceStore((state) => state.namespaces)
  const selectedNamespace = useNamespaceStore((state) => state.selectedNamespace)
  const loadNamespaces = useNamespaceStore((state) => state.load)

  useEffect(() => {
    loadNamespaces()
  }, [loadNamespaces])

  useEffect(() => {
    const targetNamespace = selectedNamespace || namespaces[0]?.id
    if (targetNamespace) {
      router.replace(applicationsPath(targetNamespace))
    }
  }, [namespaces, router, selectedNamespace])

  return <ContentPage title="Namespaces">Loading...</ContentPage>
}
