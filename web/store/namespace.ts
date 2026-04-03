import { create } from "zustand"
import { persist } from "zustand/middleware"
import { fetchNamespaces } from "@/lib/api/namespaces"

interface NamespaceItem {
  id: string
  name: string
}

interface NamespaceState {
  namespaces: NamespaceItem[]
  selectedNamespace: string
  loaded: boolean
  load: () => Promise<void>
  reload: () => Promise<void>
  setSelectedNamespace: (ns: string) => void
}

export const useNamespaceStore = create<NamespaceState>()(
  persist(
    (set, get) => ({
      namespaces: [],
      selectedNamespace: "",
      loaded: false,

      load: async () => {
        if (get().loaded) return
        try {
          const res = await fetchNamespaces()
          if (res.data && Array.isArray(res.data)) {
            const nsList = res.data.map((ns) => ({ id: ns.name, name: ns.name }))
            const { selectedNamespace } = get()
            const validNs = selectedNamespace && nsList.some((ns) => ns.id === selectedNamespace)
              ? selectedNamespace
              : nsList[0]?.id ?? ""
            set({ namespaces: nsList, selectedNamespace: validNs, loaded: true })
          }
        } catch {
          set({ loaded: true })
        }
      },

      reload: async () => {
        set({ loaded: false })
        await get().load()
      },

      setSelectedNamespace: (ns: string) => {
        set({ selectedNamespace: ns })
      },
    }),
    {
      name: "oops-namespace",
      partialize: (state) => ({ selectedNamespace: state.selectedNamespace }),
    }
  )
)
