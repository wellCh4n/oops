import { SystemItem } from "@/types/system";
import request from "@/utils/request";

export const fetchSystem = () => {
  return request.get<SystemItem[]>('/api/system');
}

export const updateSystem = (systemInfos: SystemItem[]) => {
  return request.put('/api/system', systemInfos);
}
