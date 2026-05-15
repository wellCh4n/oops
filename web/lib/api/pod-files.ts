import { apiFetch } from "./client"
import { API_BASE_URL } from "./config"
import { getToken } from "@/lib/auth"
import { ApiResponse } from "./types"

type PodFileType = "DIRECTORY" | "FILE" | "SYMLINK" | "OTHER"

export interface PodFileEntry {
  name: string
  path: string
  type: PodFileType
  size: number | null
}

export async function listPodDirectory(params: {
  namespace: string
  name: string
  pod: string
  env: string
  path: string
  container?: string
}): Promise<PodFileEntry[]> {
  const search = new URLSearchParams()
  search.set("env", params.env)
  search.set("path", params.path)
  if (params.container) {
    search.set("container", params.container)
  }
  const res = await apiFetch(
    `/api/namespaces/${encodeURIComponent(params.namespace)}/applications/${encodeURIComponent(params.name)}/pods/${encodeURIComponent(params.pod)}/files?${search.toString()}`,
  )
  const body = (await res.json()) as ApiResponse<PodFileEntry[]>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to list directory")
  }
  return body.data ?? []
}

export function getPodFileDownloadUrl(params: {
  namespace: string
  name: string
  pod: string
  env: string
  path: string
  container?: string
}): string {
  const search = new URLSearchParams()
  const token = getToken()
  if (!token) {
    throw new Error("Authentication required")
  }
  search.set("env", params.env)
  search.set("path", params.path)
  search.set("token", token)
  if (params.container) {
    search.set("container", params.container)
  }
  return `${API_BASE_URL}/api/namespaces/${encodeURIComponent(params.namespace)}/applications/${encodeURIComponent(params.name)}/pods/${encodeURIComponent(params.pod)}/files/download?${search.toString()}`
}
