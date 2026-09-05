import { create } from "zustand"
import { persist } from "zustand/middleware"

/**
 * The pipeline list's "mine / all" toggle, the counterpart of the application
 * list's owner filter. A preference, not a location: it is drawn on the toggle
 * itself, so remembering it cannot leave anyone wondering where they are.
 */
interface OperatorFilterState {
  mine: boolean
  setMine: (value: boolean) => void
}

export const useOperatorFilterStore = create<OperatorFilterState>()(
  persist(
    (set) => ({
      mine: true,
      setMine: (value) => set({ mine: value }),
    }),
    {
      name: "oops:operator-filter",
    }
  )
)
