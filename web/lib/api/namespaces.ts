import { apiFetch } from "./client"
import { ApiResponse, Namespace } from "./types"

export async function fetchNamespaces(): Promise<ApiResponse<Namespace[]>> {
  const res = await apiFetch(`/api/namespaces`)
  if (!res.ok) {
    throw new Error("Failed to fetch namespaces")
  }
  return res.json()
}

export async function createNamespace(name: string, description?: string): Promise<ApiResponse<boolean>> {
  const res = await apiFetch(`/api/namespaces`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ name, description }),
  })
  if (!res.ok) {
    throw new Error("Failed to create namespace")
  }
  return res.json()
}

export async function updateNamespace(name: string, description: string): Promise<ApiResponse<boolean>> {
  const res = await apiFetch(`/api/namespaces`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ name, description }),
  })
  if (!res.ok) {
    throw new Error("Failed to update namespace")
  }
  return res.json()
}
