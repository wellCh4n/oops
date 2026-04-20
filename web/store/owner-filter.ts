import { create } from "zustand"
import { persist } from "zustand/middleware"

interface OwnerFilterState {
  ownerOnly: boolean
  setOwnerOnly: (value: boolean) => void
}

export const useOwnerFilterStore = create<OwnerFilterState>()(
  persist(
    (set) => ({
      ownerOnly: true,
      setOwnerOnly: (value) => set({ ownerOnly: value }),
    }),
    {
      name: "oops:owner-filter",
    }
  )
)
