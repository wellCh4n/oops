import { apiFetch } from "./client"
import { API_BASE_URL } from "./config"
import { getToken } from "@/lib/auth"
import { ApiResponse } from "./types"
import { PodFileContent, PodFileEntry } from "./pod-files"

export type { PodFileEntry, PodFileContent }

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

export async function getSandboxFileContent(params: { id: string; path: string }): Promise<PodFileContent> {
  const search = new URLSearchParams()
  search.set("path", params.path)
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files/content?${search.toString()}`)
  const body = (await res.json()) as ApiResponse<PodFileContent>
  if (!res.ok || !body.success || !body.data) {
    throw new Error(body.message || "Failed to load file")
  }
  return body.data
}

export async function saveSandboxFileContent(params: { id: string; path: string; content: string }): Promise<void> {
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files/content`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: params.path, content: params.content }),
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to save file")
  }
}

export async function uploadSandboxFile(params: { id: string; path: string; file: File }): Promise<void> {
  const search = new URLSearchParams()
  search.set("path", params.path)
  const form = new FormData()
  form.append("file", params.file)
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files/upload?${search.toString()}`, {
    method: "POST",
    body: form,
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to upload file")
  }
}

export async function deleteSandboxPath(params: { id: string; path: string }): Promise<void> {
  const search = new URLSearchParams()
  search.set("path", params.path)
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files?${search.toString()}`, {
    method: "DELETE",
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to delete")
  }
}

export async function createSandboxDirectory(params: { id: string; path: string }): Promise<void> {
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files/directory`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: params.path }),
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to create directory")
  }
}

export async function renameSandboxPath(params: { id: string; fromPath: string; toPath: string }): Promise<void> {
  const res = await apiFetch(`/api/sandbox/instances/${encodeURIComponent(params.id)}/files/rename`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fromPath: params.fromPath, toPath: params.toPath }),
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to rename")
  }
}
