import { Result } from '@/types/result';
import request from 'umi-request';

const env = process.env.NODE_ENV;
const baseUrlMap = {
  development: 'localhost:8080',
  production: 'localhost:8080',
  test: 'localhost:8080'
};
const baseUrl = baseUrlMap[env] || 'localhost:8080';

request.interceptors.request.use((url, options) => {
  return {
    url: `http://${baseUrl}${url}`,
    options
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

export default {
  get,
  post,
  put,
  del,
  baseUrl
};