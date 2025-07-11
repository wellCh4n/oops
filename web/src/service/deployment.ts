import request from "@/utils/request"

export const deployApplication = (namespace: string, appName: string) => {
  return request.post<string>(`/api/namespaces/${namespace}/applications/${appName}/deployments`)
}