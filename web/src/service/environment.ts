import { EnvironmentItem } from "@/types/environment"
import requests from "@/utils/request"

export const fetchEnvironmentList = () => {
  return requests.get<EnvironmentItem[]>(`/api/environments`)
}

export const createEnvironment = (data: EnvironmentItem) => {
  return requests.post<EnvironmentItem>(`/api/environments`, data)
}

export const updateEnvironment = (id: string, data: EnvironmentItem) => {
  return requests.put<EnvironmentItem>(`/api/environments/${id}`, data)
}

export const deleteEnvironment = (id: string) => {
  return requests.del<EnvironmentItem>(`/api/environments/${id}`)
}
