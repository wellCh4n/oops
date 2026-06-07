import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface AssetEntry {
  type: "FOLDER" | "FILE"
  name: string
  key: string
  size: number
  lastModified: string | null
  contentType: string | null
  publicUrl: string | null
  signedUrl: string | null
}

export interface AssetUploadUrl {
  objectKey: string
  objectUrl: string
  uploadUrl: string
  headers: Record<string, string>
}

export async function fetchEntries(path: string): Promise<AssetEntry[]> {
  const params = new URLSearchParams()
  if (path) params.set("path", path)
  const query = params.toString()
  const res = await apiFetch(query ? `/api/assets?${query}` : "/api/assets")
  const data = await res.json() as ApiResponse<AssetEntry[]>
  if (!data.success) {
    throw new Error(data.message || "Failed to fetch assets")
  }
  return data.data
}

async function requestUploadUrl(request: {
  path: string
  fileName: string
  contentType: string
  fileSize: number
}): Promise<AssetUploadUrl> {
  const res = await apiFetch("/api/assets/upload-url", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  })
  const data = await res.json() as ApiResponse<AssetUploadUrl>
  if (!data.success || !data.data) {
    throw new Error(data.message || "Failed to create upload url")
  }
  return data.data
}

export async function uploadAsset(file: File, path: string): Promise<void> {
  const upload = await requestUploadUrl({
    path,
    fileName: file.name,
    contentType: file.type || "application/octet-stream",
    fileSize: file.size,
  })

  const headers = new Headers()
  Object.entries(upload.headers || {}).forEach(([key, value]) => {
    headers.set(key, value)
  })

  const putRes = await fetch(upload.uploadUrl, {
    method: "PUT",
    headers,
    body: file,
  })
  if (!putRes.ok) {
    throw new Error("Upload failed")
  }
}

export async function deleteEntry(key: string): Promise<void> {
  const res = await apiFetch(`/api/assets?key=${encodeURIComponent(key)}`, { method: "DELETE" })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to delete")
  }
}
