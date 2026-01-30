"use client"

import dynamic from "next/dynamic"
import { useParams, useSearchParams } from "next/navigation"

const TerminalView = dynamic(() => import("@/components/terminal-view"), {
  ssr: false,
  loading: () => <div className="p-4 text-white">Loading terminal...</div>
})

export default function TerminalPage() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("env")

  if (!env) {
    return <div className="p-4">Missing env parameter</div>
  }

  return <TerminalView namespace={namespace} name={name} pod={pod} env={env} />
}
