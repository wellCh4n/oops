import { PipelineItem } from "@/types/pipeline"
import request from "@/utils/request"

export const fetchPipelines = (namespace: string, appName: string) => {
  return request.get<PipelineItem[]>(`/api/namespaces/${namespace}/applications/${appName}/pipelines`)
}

export const fetchPipeline = (namespace: string, appName: string,  pipelineId: string) => {
  return request.get<PipelineItem>(`/api/namespaces/${namespace}/applications/${appName}/pipelines/${pipelineId}`)
}

export const watchPipeline = (namespace: string, appName: string, pipelineId: string) => {
  return new EventSource(`http://${request.baseUrl}/api/namespaces/${namespace}/applications/${appName}/pipelines/${pipelineId}/watch`)
}

export const stopPipeline = (namespace: string, appName: string, pipelineId: string) => {
  return request.put(`/api/namespaces/${namespace}/applications/${appName}/pipelines/${pipelineId}/stop`)
}