import { Suspense } from "react"
import PageClient from "./page-client"

export default function AppsPage({
  searchParams,
}: {
  searchParams: Promise<{ namespace?: string }>
}) {
  return (
    <Suspense fallback={<div className="p-4">Loading...</div>}>
      <PageClient searchParams={searchParams} />
    </Suspense>
  )
}
