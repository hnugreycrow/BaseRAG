import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string
}

export class ApiRequestError extends Error {
  readonly code?: string
  readonly requestId?: string
  readonly status?: number

  constructor(
    message: string,
    options: { code?: string; requestId?: string; status?: number } = {},
  ) {
    super(message)
    this.name = 'ApiRequestError'
    this.code = options.code
    this.requestId = options.requestId
    this.status = options.status
  }
}

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60_000,
  headers: {
    Accept: 'application/json',
  },
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiResponse<unknown>>) => {
    const response = error.response
    const body = response?.data

    if (body?.message) {
      return Promise.reject(
        new ApiRequestError(body.message, {
          code: body.code,
          requestId: body.requestId,
          status: response?.status,
        }),
      )
    }

    const message = response
      ? `服务请求失败（${response.status}）`
      : '无法连接服务，请检查后端是否已启动'

    return Promise.reject(new ApiRequestError(message, { status: response?.status }))
  },
)

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)

  if (response.status === 204) {
    return undefined as T
  }

  const body = response.data

  if (body.code !== 'SUCCESS') {
    throw new ApiRequestError(body.message || '请求未成功', {
      code: body.code,
      requestId: body.requestId,
      status: response.status,
    })
  }

  return body.data
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiRequestError && error.requestId) {
    return `${error.message} · 请求 ${error.requestId}`
  }

  return error instanceof Error ? error.message : '发生未知错误，请稍后重试'
}

export { http }
