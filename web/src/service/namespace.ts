import request from "@/utils/request";

export const fetchNamespaceList = () => {
  return request.get<string[]>('/api/namespaces')
}