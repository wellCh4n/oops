import { Skeleton } from "@/components/ui/skeleton"

function ApplicationEditorCardSkeleton({ children }: { children: React.ReactNode }) {
  return (
    <div className="border rounded-lg overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b">
        <Skeleton className="h-4 w-4 rounded-sm" />
        <Skeleton className="h-4 w-28" />
      </div>
      <div className="flex flex-col gap-4 p-4">
        {children}
      </div>
    </div>
  )
}

export function ApplicationEditorTabSkeleton() {
  return (
    <div className="flex w-full flex-col gap-4">
      <ApplicationEditorCardSkeleton>
        <div className="flex gap-2">
          <Skeleton className="h-9 w-20" />
          <Skeleton className="h-9 w-20" />
        </div>
        <Skeleton className="h-48 w-full" />
      </ApplicationEditorCardSkeleton>
      <ApplicationEditorCardSkeleton>
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-24 w-full" />
      </ApplicationEditorCardSkeleton>
    </div>
  )
}
