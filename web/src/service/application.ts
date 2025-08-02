import { ApplicationItem, ApplicationPodItem } from "@/types/application"
import request from "@/utils/request"

export const fetchApplicationList = (namespace: string) => {
  return request.get<ApplicationItem[]>(`/api/namespaces/${namespace}/applications`)
}

export const fetchApplicationDetail = (namespace: string, name: string) => {
  return request.get<ApplicationItem>(`/api/namespaces/${namespace}/applications/${name}`)
}

export const fetchApplicationStatus = (name: string) => {
  return request.get<ApplicationPodItem[]>(`/api/namespaces/default/applications/${name}/status`)
}

export const restartApplication = (name: string, podName: string) => {
  return request.put(`/api/namespaces/default/applications/${name}/pods/${podName}/restart`)
}

export const openApplicationPodTerminal = (name: string, podName: string) => {
  return request.ws(`/api/namespaces/default/applications/${name}/pods/${podName}/terminal`)
}

export const openApplicationPodExplorer = (name: string, podName: string) => {
  return request.ws(`/api/namespaces/default/applications/${name}/pods/${podName}/explorer`);
}

export const fetchApplicationPodLog = (name: string, podName: string) => {
  return request.sse(`/api/namespaces/default/applications/${name}/pods/${podName}/log`)
}

export const createApplication = (namespace: string, application: ApplicationItem) => {
  return request.post(`/api/namespaces/${namespace}/applications`, application)
}

export const updateApplication = (namespace: string, application: ApplicationItem) => {
  return request.put(`/api/namespaces/${namespace}/applications/${application.name}`, application)
}
