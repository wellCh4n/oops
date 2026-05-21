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

export interface PodFileContent {
  path: string
  content: string
}

interface PodFileBaseParams {
  namespace: string
  name: string
  pod: string
  env: string
  container?: string
}

function buildFileBaseUrl(params: PodFileBaseParams): string {
  return `/api/namespaces/${encodeURIComponent(params.namespace)}/applications/${encodeURIComponent(params.name)}/pods/${encodeURIComponent(params.pod)}/files`
}

function appendCommonParams(search: URLSearchParams, params: PodFileBaseParams) {
  search.set("env", params.env)
  if (params.container) {
    search.set("container", params.container)
  }
}

export async function listPodDirectory(params: PodFileBaseParams & { path: string }): Promise<PodFileEntry[]> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  search.set("path", params.path)
  const res = await apiFetch(`${buildFileBaseUrl(params)}?${search.toString()}`)
  const body = (await res.json()) as ApiResponse<PodFileEntry[]>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to list directory")
  }
  return body.data ?? []
}

export function getPodFileDownloadUrl(params: PodFileBaseParams & { path: string }): string {
  const search = new URLSearchParams()
  const token = getToken()
  if (!token) {
    throw new Error("Authentication required")
  }
  appendCommonParams(search, params)
  search.set("path", params.path)
  search.set("token", token)
  return `${API_BASE_URL}${buildFileBaseUrl(params)}/download?${search.toString()}`
}

export async function getPodFileContent(params: PodFileBaseParams & { path: string }): Promise<PodFileContent> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  search.set("path", params.path)
  const res = await apiFetch(`${buildFileBaseUrl(params)}/content?${search.toString()}`)
  const body = (await res.json()) as ApiResponse<PodFileContent>
  if (!res.ok || !body.success || !body.data) {
    throw new Error(body.message || "Failed to load file")
  }
  return body.data
}

export async function savePodFileContent(params: PodFileBaseParams & { path: string; content: string }): Promise<void> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  const res = await apiFetch(`${buildFileBaseUrl(params)}/content?${search.toString()}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: params.path, content: params.content }),
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to save file")
  }
}

export async function uploadPodFile(params: PodFileBaseParams & { path: string; file: File }): Promise<void> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  search.set("path", params.path)
  const form = new FormData()
  form.append("file", params.file)
  const res = await apiFetch(`${buildFileBaseUrl(params)}/upload?${search.toString()}`, {
    method: "POST",
    body: form,
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to upload file")
  }
}

export async function deletePodPath(params: PodFileBaseParams & { path: string }): Promise<void> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  search.set("path", params.path)
  const res = await apiFetch(`${buildFileBaseUrl(params)}?${search.toString()}`, {
    method: "DELETE",
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to delete")
  }
}

export async function renamePodPath(params: PodFileBaseParams & { fromPath: string; toPath: string }): Promise<void> {
  const search = new URLSearchParams()
  appendCommonParams(search, params)
  const res = await apiFetch(`${buildFileBaseUrl(params)}/rename?${search.toString()}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fromPath: params.fromPath, toPath: params.toPath }),
  })
  const body = (await res.json()) as ApiResponse<void>
  if (!res.ok || !body.success) {
    throw new Error(body.message || "Failed to rename")
  }
}

