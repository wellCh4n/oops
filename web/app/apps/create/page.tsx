import { Suspense } from "react"
import PageClient from "./page-client"

export default function CreateAppPage({
  searchParams,
}: {
  searchParams: Promise<{ namespace?: string }>
}) {
  return (
    <Suspense fallback={<div className="p-4">加载中...</div>}>
      <PageClient searchParams={searchParams} />
    </Suspense>
  )
}
