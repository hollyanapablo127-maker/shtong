import {forwardDevOrigin} from './dev-api-proxy'

const apiTarget = process.env.SHTONG_DEV_API_TARGET || 'http://localhost:8080'

export default defineNuxtConfig({
  buildDir: process.env.SHTONG_NUXT_BUILD_DIR || '.nuxt',
  ssr: false,
  devtools: {enabled: false},
  app: {head: {title: '生化通', meta: [{name: 'viewport', content: 'width=device-width, initial-scale=1'}]}},
  css: ['~/assets/main.css', '~/assets/profile.css'],
  runtimeConfig: {public: {apiBase: process.env.NUXT_PUBLIC_API_BASE || '/v1'}},
  typescript: {strict: true},
  vite: {server: {proxy: {
    '/v1': {
      target: apiTarget,
      changeOrigin: true,
      configure(proxy) {
        proxy.on('proxyReq', (proxyRequest, request) => forwardDevOrigin(proxyRequest, request, apiTarget))
      },
    },
  }}},
})
