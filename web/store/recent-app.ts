import { create } from "zustand"
import { persist, createJSONStorage } from "zustand/middleware"

/**
 * The one thing OOPS remembers across visits: the application the user last
 * opened, shown as a shortcut at the top of the command palette.
 *
 * It is an entry point, not a context. Nothing reads it to pre-select a filter
 * or to decide what a page shows — every page is fully described by its URL,
 * so a user is never "switched" somewhere by state they cannot see. Written
 * only when an application detail page is opened or the palette jumps to one.
 */

export interface RecentApp {
  namespace: string
  name: string
  description?: string
  ownerName?: string
  /** Carried so the shortcut renders the same mark as everywhere else. */
  icon?: string
}

interface RecentAppState {
  app: RecentApp | null
  remember: (app: RecentApp) => void
}

export const useRecentAppStore = create<RecentAppState>()(
  persist(
    (set) => ({
      app: null,
      remember: (app) => set({ app }),
    }),
    {
      name: "oops:recent-app",
      storage: createJSONStorage(() => localStorage),
    }
  )
)
