import { SystemItem } from "@/types/system";
import request from "@/utils/request";

export const fetchSystem = () => {
  return request.get<SystemItem[]>('/api/system');


}