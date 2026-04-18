import { create } from "zustand"
import { getFeatures, Features } from "@/lib/api/features"

interface FeaturesState {
  features: Features
  loaded: boolean
  load: () => Promise<void>
}

export const useFeaturesStore = create<FeaturesState>((set, get) => ({
  features: { feishu: false, ide: false, ideHost: null, ideHttps: false, objectStorage: false },
  loaded: false,
  load: async () => {
    if (get().loaded) return
    const features = await getFeatures()
    set({ features, loaded: true })
  },
}))
