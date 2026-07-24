import { create } from "zustand"
import { persist } from "zustand/middleware"

export const DEFAULT_PAGE_SIZE = 10

interface PageSizeState {
  /** Page size shared by every paginated list */
  pageSize: number
  setPageSize: (size: number) => void
}

export const usePageSizeStore = create<PageSizeState>()(
  persist(
    (set) => ({
      pageSize: DEFAULT_PAGE_SIZE,
      setPageSize: (size) => set({ pageSize: size }),
    }),
    {
      name: "oops:page-size",
    }
  )
)

/**
 * Resolves the page size for a list: an explicit value (typically the `size` URL query
 * param) wins, otherwise the last size the user picked is restored.
 */
export function usePageSize(explicitSize?: string | number | null) {
  const pageSize = usePageSizeStore((state) => state.pageSize)
  const setPageSize = usePageSizeStore((state) => state.setPageSize)

  const parsed = explicitSize === null || explicitSize === undefined || explicitSize === ""
    ? NaN
    : Number(explicitSize)
  const size = Number.isFinite(parsed) && parsed > 0 ? parsed : pageSize

  return { size, rememberSize: setPageSize }
}
