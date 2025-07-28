import requests from "@/utils/request"
import { ConfigMapItem } from "@/types/configmap"

export const fetchConfigMap = (namespace: string, appName: string) => {
  return requests.get<ConfigMapItem[]>(`/api/namespaces/${namespace}/applications/${appName}/configmaps`)
}

export const updateConfigMap = (namespace: string, appName: string, configMap: ConfigMapItem[]) => {
  return requests.put(`/api/namespaces/${namespace}/applications/${appName}/configmaps`, configMap)
}
