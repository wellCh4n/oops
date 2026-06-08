import { create } from "zustand"
import { persist } from "zustand/middleware"

const DEFAULT_EXPANDED_GROUPS = ["nav.appManagement"]

interface SidebarNavState {
  expandedGroups: string[]
  setGroupExpanded: (groupTitle: string, expanded: boolean) => void
}

export const useSidebarNavStore = create<SidebarNavState>()(
  persist(
    (set) => ({
      expandedGroups: DEFAULT_EXPANDED_GROUPS,
      setGroupExpanded: (groupTitle, expanded) => {
        set((state) => ({
          expandedGroups: expanded
            ? Array.from(new Set([...state.expandedGroups, groupTitle]))
            : state.expandedGroups.filter((title) => title !== groupTitle),
        }))
      },
    }),
    {
      name: "oops:sidebar-nav",
      partialize: (state) => ({ expandedGroups: state.expandedGroups }),
    }
  )
)
