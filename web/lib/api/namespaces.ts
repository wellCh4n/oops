import { API_BASE_URL } from "./config"
import { ApiResponse } from "./types"

export async function fetchNamespaces(): Promise<ApiResponse<string[]>> {
  const res = await fetch(`${API_BASE_URL}/api/namespaces`)
  if (!res.ok) {
    throw new Error("Failed to fetch namespaces")
  }
  return res.json()
}

export async function createNamespace(name: string): Promise<ApiResponse<boolean>> {
  const res = await fetch(`${API_BASE_URL}/api/namespaces`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ name }),
  })
  if (!res.ok) {
    throw new Error("Failed to create namespace")
  }
  return res.json()
}
