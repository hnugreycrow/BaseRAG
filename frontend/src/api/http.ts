import axios, { AxiosError } from "axios";

type ApiErrorBody = {
  message?: string;
  requestId?: string;
};

export const http = axios.create({
  baseURL: "/api",
  timeout: 60_000,
});

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorBody>) => {
    const body = error.response?.data;
    const message = body?.message
      ? body.message + (body.requestId ? ` · 请求 ${body.requestId}` : "")
      : error.response
        ? `服务请求失败，请检查后端连接（${error.response.status}）`
        : "无法连接服务，请检查后端状态";
    return Promise.reject(new Error(message));
  },
);

export function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "无法连接服务，请检查后端状态";
}
