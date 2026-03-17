import { API_BASE_URL } from "./config"
import { ApiResponse, NodeStatus } from "./types"

export async function fetchNodes(env: string): Promise<ApiResponse<NodeStatus[]>> {
  const params = new URLSearchParams()
  params.set("env", env)

  const res = await fetch(`${API_BASE_URL}/api/nodes?${params.toString()}`)
  if (!res.ok) {
    throw new Error("Failed to fetch nodes")
  }
  return res.json()
}

