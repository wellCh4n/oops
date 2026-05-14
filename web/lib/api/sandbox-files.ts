import { apiFetch } from "./client"
import { API_BASE_URL } from "./config"
import { getToken } from "@/lib/auth"
import { ApiResponse } from "./types"
import { PodFileEntry } from "./pod-files"

export type { PodFileEntry }

export async function listSandboxDirectory(params: {
  id: string
  path: string
}): Promise<PodFileEntry[]> {
  const search = new URLSearchParams()
  search.set("path", params.path)
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files?${search.toString()}`)
  const body = (await res.json()) as ApiResponse<PodFileEntry[]>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to list directory")
  }
  return body.data ?? []
}

export function getSandboxFileDownloadUrl(params: {
  id: string
  path: string
}): string {
  const token = getToken()
  if (!token) {
    throw new Error("Authentication required")
  }
  const search = new URLSearchParams()
  search.set("path", params.path)
  search.set("token", token)
  return `${API_BASE_URL}/api/sandbox/instances/${encodeURIComponent(params.id)}/files/download?${search.toString()}`
}
