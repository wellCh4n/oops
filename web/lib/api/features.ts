import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface Features {
  feishu: boolean
  ide: boolean
  ideHost: string | null
  ideHttps: boolean
  objectStorage: boolean
}

export async function getFeatures(): Promise<Features> {
  try {
    const res = await apiFetch("/api/features")
    const data = await res.json() as ApiResponse<Features>
    return data.data ?? { feishu: false, ide: false, ideHost: null, ideHttps: false, objectStorage: false }
  } catch {
    return { feishu: false, ide: false, ideHost: null, ideHttps: false, objectStorage: false }
  }
}
