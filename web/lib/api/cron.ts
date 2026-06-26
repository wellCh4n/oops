import { ApiResponse } from "./types"
import { apiFetch } from "./client"

export async function fetchCronNextRuns(expression: string, count = 1): Promise<ApiResponse<string[]>> {
  const params = new URLSearchParams()
  params.set("expression", expression)
  params.set("count", String(count))

  const res = await apiFetch(`/api/cron/next?${params.toString()}`)
  return res.json() as Promise<ApiResponse<string[]>>
}
