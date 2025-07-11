import { PipelineItem } from "@/types/pipeline"
import request from "@/utils/request"

export const fetchPipelines = (namespace: string, appName: string) => {
  return request.get<PipelineItem[]>(`/api/namespaces/${namespace}/applications/${appName}/pipelines`)
}