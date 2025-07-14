import { ApplicationItem } from "@/types/application";
import { PipelineItem } from "@/types/pipeline";
import request from "@/utils/request";

export const queryPipelines = (params: any) => {
  return request.post<PipelineItem[]>(`/api/index/pipelines`, params);
};

export const queryApplications = (params: any) => {
  return request.post<ApplicationItem[]>(`/api/index/applications`, params);
}
