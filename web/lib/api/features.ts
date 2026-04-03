import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface Features {
  feishu: boolean
  ide: boolean
}

export async function getFeatures(): Promise<Features> {
  try {
    const res = await apiFetch("/api/features")
    const data: ApiResponse<Features> = await res.json()
    return data.data ?? { feishu: false, ide: false }
  } catch {
    return { feishu: false, ide: false }
  }
}
