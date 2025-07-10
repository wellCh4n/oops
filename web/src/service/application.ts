import { ApplicationItem, ApplicationPodItem } from "@/types/application"
import request from "@/utils/request"

export const fetchApplicationList = () => {
  return request.get<ApplicationItem[]>('/api/namespaces/default/applications')
}

export const fetchApplicationDetail = (name: string) => {
    return request.get<ApplicationItem>(`/api/namespaces/default/applications/${name}`)
}

export const fetchApplicationStatus = (name: string) => {
    return request.get<ApplicationPodItem[]>(`/api/namespaces/default/applications/${name}/status`)
}
