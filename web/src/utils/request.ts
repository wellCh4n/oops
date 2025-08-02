import { Result } from '@/types/result';
import request from 'umi-request';
import { useEnvironmentStore } from '@/store/environment-store';
import { EventSourcePolyfill } from 'event-source-polyfill';

const env = process.env.NODE_ENV;
const baseUrlMap = {
  development: 'localhost:8080',
  production: 'localhost:8080',
  test: 'localhost:8080'
};
const baseUrl = baseUrlMap[env] || 'localhost:8080';

request.interceptors.request.use((url, options) => {
  const currentEnvironment = useEnvironmentStore.getState().currentEnvironment;
  
  return {
    url: `http://${baseUrl}${url}`,
    options: {
      ...options,
      headers: {
        'OOPS-Environment': currentEnvironment?.name || ''
      }
    }
  };
});

request.interceptors.response.use(async (response) => {
  const resData = await response.json() as Result<any>;
  if (resData.success) {
    return resData.data;
  } else {
    throw new Error(resData.message || '请求失败');
  }
});

function get<T = any>(url: string, params?: Record<string, any>) {
  return request<T>(url, {
    method: 'GET',
    params
  });
}

function put<T = any>(url: string, data?: Record<string, any>) {
  return request<T>(url, {
    method: 'PUT',
    data
  });
}

function post<T = any>(url: string, data?: Record<string, any>) {
  return request<T>(url, {
    method: 'POST',
    data
  });
}

function del<T = any>(url: string, data?: Record<string, any>) {
  return request<T>(url, {
    method: 'DELETE',
    data
  });
}

function sse(url: string) {
  const currentEnvironment = useEnvironmentStore.getState().currentEnvironment;
  return new EventSourcePolyfill(`http://${baseUrl}${url}`, {
    headers: {
      'OOPS-Environment': currentEnvironment?.name || ''
    }
  })
}

function ws(url: string) {
  const currentEnvironment = useEnvironmentStore.getState().currentEnvironment;
  return new WebSocket(`ws://${baseUrl}${url}?environment=${currentEnvironment?.name}`);
}

const requests = {
  get,
  post,
  put,
  del,
  sse,
  ws,
  baseUrl
};

export default requests;
