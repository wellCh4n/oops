import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export type DomainCertMode = "AUTO" | "UPLOADED"

export interface Domain {
  id: string
  host: string
  description?: string | null
  https: boolean
  certMode: DomainCertMode | null
  hasUploadedCert: boolean
  certSubject: string | null
  certNotAfter: string | null
  createdTime?: string
}

export interface DomainRequest {
  host: string
  description?: string
  https: boolean
  certMode?: DomainCertMode
  certPem?: string
  keyPem?: string
}

export async function fetchDomains(): Promise<Domain[]> {
  const res = await apiFetch("/api/domains")
  const data = await res.json() as ApiResponse<Domain[]>
  if (!data.success) {
    throw new Error(data.message || "Failed to fetch domains")
  }
  return data.data
}

export async function createDomain(request: DomainRequest): Promise<Domain> {
  const res = await apiFetch("/api/domains", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  })
  const data = await res.json() as ApiResponse<Domain>
  if (!data.success) {
    throw new Error(data.message || "Failed to create domain")
  }
  return data.data
}

export async function updateDomain(id: string, request: DomainRequest): Promise<Domain> {
  const res = await apiFetch(`/api/domains/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  })
  const data = await res.json() as ApiResponse<Domain>
  if (!data.success) {
    throw new Error(data.message || "Failed to update domain")
  }
  return data.data
}

export async function deleteDomain(id: string): Promise<void> {
  const res = await apiFetch(`/api/domains/${id}`, { method: "DELETE" })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to delete domain")
  }
}
