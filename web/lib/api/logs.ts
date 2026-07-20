import { apiFetch } from './client';
import type { ApiResponse, Page, Log, OperationSource } from './types';

export type { Log, OperationSource };

export interface LogsQueryParams {
  namespace?: string;
  resourceType?: string;
  resourceId?: string;
  userId?: string;
  source?: OperationSource;
  startTime?: string;
  endTime?: string;
  page?: number;
  size?: number;
}

// Global logs
export async function getLogs(params: LogsQueryParams): Promise<ApiResponse<Page<Log>>> {
  const queryParams = new URLSearchParams();

  if (params.namespace) queryParams.append('namespace', params.namespace);
  if (params.resourceType) queryParams.append('resourceType', params.resourceType);
  if (params.resourceId) queryParams.append('resourceId', params.resourceId);
  if (params.userId) queryParams.append('userId', params.userId);
  if (params.source) queryParams.append('source', params.source);
  if (params.startTime) queryParams.append('startTime', params.startTime);
  if (params.endTime) queryParams.append('endTime', params.endTime);
  if (params.page !== undefined) queryParams.append('page', params.page.toString());
  if (params.size !== undefined) queryParams.append('size', params.size.toString());

  const response = await apiFetch(`/api/logs?${queryParams.toString()}`);
  if (!response.ok) {
    throw new Error('Failed to fetch logs');
  }
  return response.json() as Promise<ApiResponse<Page<Log>>>;
}

