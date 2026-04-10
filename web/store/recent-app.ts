import { create } from "zustand"
import { persist } from "zustand/middleware"

interface RecentApp {
  namespace: string
  name: string
  description?: string
  ownerName?: string
}

interface RecentAppState {
  recentApp: RecentApp | null
  setRecentApp: (app: RecentApp | null) => void
}

export const useRecentAppStore = create<RecentAppState>()(
  persist(
    (set) => ({
      recentApp: null,
      setRecentApp: (app) => set({ recentApp: app }),
    }),
    {
      name: "oops:recent-app",
    }
  )
)
