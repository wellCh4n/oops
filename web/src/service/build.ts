import { BuildStorageItem } from "@/types/build"
import requests from "@/utils/request"

export const fetchBuildStorageList = (namespace: string, appName: string) => {
  return requests.get<BuildStorageItem[]>(`/api/namespaces/${namespace}/applications/${appName}/build/storages`)
}

export const createApplicationBuildStorage = (namespace: string, appName: string, buildStorage: BuildStorageItem) => {
  return requests.post(`/api/namespaces/${namespace}/applications/${appName}/build/storages`, buildStorage)
}

export const deleteApplicationBuildStorage = (namespace: string, appName: string, buildStorage: BuildStorageItem) => {
  return requests.del(`/api/namespaces/${namespace}/applications/${appName}/build/storages`, buildStorage)
}