import requests from "@/utils/request"

export const fetchConfigMap = (namespace: string, appName: string) => {
  return requests.get<Record<string, string>[]>(`/api/namespaces/${namespace}/applications/${appName}/configmaps`)
}

export const updateConfigMap = (namespace: string, appName: string, configMap: Record<string, string>[]) => {
  return requests.put(`/api/namespaces/${namespace}/applications/${appName}/configmaps`, configMap)
}
