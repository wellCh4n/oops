import { EnvironmentItem } from "@/types/environment"
import requests from "@/utils/request"

export const fetchEnvironmentList = () => {
  return requests.get<EnvironmentItem[]>(`/api/environments`)
}