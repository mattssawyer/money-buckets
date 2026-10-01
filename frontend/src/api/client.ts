import axios, { type AxiosInstance } from 'axios'

type AccessTokenProvider = () => Promise<string | null>

let accessTokenProvider: AccessTokenProvider | undefined

export function setAccessTokenProvider(provider: AccessTokenProvider): void {
  accessTokenProvider = provider
}

export function createApiClient(): AxiosInstance {
  const baseURL = import.meta.env.VITE_API_BASE_URL

  if (!baseURL) {
    throw new Error('VITE_API_BASE_URL is not set')
  }

  return axios.create({
    baseURL,
    headers: {
      'Content-Type': 'application/json',
    },
  })
}

export const apiClient = createApiClient()

apiClient.interceptors.request.use(async (config) => {
  const accessToken = await accessTokenProvider?.()

  if (accessToken) {
    config.headers.set('Authorization', `Bearer ${accessToken}`)
  }

  return config
})
