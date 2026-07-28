import { create } from "zustand"
import { persist, createJSONStorage } from "zustand/middleware"
import { fetchNamespaces } from "@/lib/api/namespaces"

/**
 * The single piece of "what am I currently looking at" state, shared by the
 * apps / pipelines / IDEs pages, the sidebar and the command palette.
 *
 * It replaces the three stores that used to hold overlapping slices of this
 * (`oops-namespace`, `oops:recent-app`, plus per-page URL defaulting). Keeping
 * namespace / app / env in one persisted object is the point: as separate keys
 * with separate writers they could — and did — drift out of sync.
 *
 * Only user actions write here. Nothing in this store guesses a value, and
 * selecting an app never rewrites the namespace scope.
 */

export const ALL_NAMESPACES = "all"

export interface AppRef {
  /** Always a concrete namespace, even when the scope is ALL_NAMESPACES. */
  namespace: string
  name: string
  description?: string
  ownerName?: string
  /** Carried so the remembered app renders the same mark as everywhere else. */
  icon?: string
}

export interface NamespaceItem {
  id: string
  name: string
}

interface WorkContextState {
  // Server data, not part of the context itself.
  namespaces: NamespaceItem[]
  loaded: boolean

  /** Filter scope — a namespace name or ALL_NAMESPACES. */
  namespace: string
  app: AppRef | null
  /** Environment name; "" means none selected. */
  env: string

  load: () => Promise<void>
  reload: () => Promise<void>
  /** Dumb assignment. All "changing X clears Y" policy lives in useWorkContext. */
  setContext: (patch: Partial<Pick<WorkContextState, "namespace" | "app" | "env">>) => void
  /** Called when the user opens an application detail page. */
  enterApp: (app: AppRef) => void
}

// Swap for `() => sessionStorage` to scope the remembered context to a single
// browser session instead of persisting it across days.
const contextStorage = createJSONStorage(() => localStorage)

export const useWorkContextStore = create<WorkContextState>()(
  persist(
    (set, get) => ({
      namespaces: [],
      loaded: false,
      namespace: "",
      app: null,
      env: "",

      load: async () => {
        if (get().loaded) return
        try {
          const res = await fetchNamespaces()
          if (!res.data || !Array.isArray(res.data)) return
          const namespaceList = res.data.map((namespace) => ({ id: namespace.name, name: namespace.name }))
          const known = (name: string) => namespaceList.some((namespace) => namespace.id === name)

          const { namespace, app } = get()
          // A remembered namespace that no longer exists falls back to the first
          // one; a remembered app under a vanished namespace is dropped rather
          // than silently re-pointed at something the user never chose.
          const validNamespace = namespace === ALL_NAMESPACES || known(namespace)
            ? namespace
            : namespaceList[0]?.id ?? ""
          const validApp = app && known(app.namespace) ? app : null

          set({
            namespaces: namespaceList,
            namespace: validNamespace,
            app: validApp,
            env: validApp ? get().env : "",
            loaded: true,
          })
        } catch {
          set({ loaded: true })
        }
      },

      reload: async () => {
        set({ loaded: false })
        await get().load()
      },

      setContext: (patch) => set(patch),

      // Opening an app's detail page is an explicit choice, so the scope follows
      // it — otherwise the app would sit outside the current scope and get
      // dropped from every link built afterwards. The "all" scope is a
      // deliberate cross-namespace view and is left alone.
      enterApp: (app) => set((state) => ({
        app,
        namespace: state.namespace === ALL_NAMESPACES ? state.namespace : app.namespace,
      })),
    }),
    {
      name: "oops:work-context",
      storage: contextStorage,
      partialize: (state) => ({
        namespace: state.namespace,
        app: state.app,
        env: state.env,
      }),
    }
  )
)
