import { ApiResponse } from "./types"
import { apiFetch } from "./client"

export async function fetchServiceAccounts(namespace: string, env: string): Promise<ApiResponse<string[]>> {
  const params = new URLSearchParams()
  params.set("env", env)

  const res = await apiFetch(`/api/namespaces/${namespace}/service-accounts?${params.toString()}`)
  if (!res.ok) {
    throw new Error("Failed to fetch service accounts")
  }
  return res.json() as Promise<ApiResponse<string[]>>
}
