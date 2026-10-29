import {defineConfig} from 'vite';import vue from '@vitejs/plugin-vue';

const apiTarget = process.env.SHTONG_DEV_API_TARGET || 'http://localhost:8080'

export default defineConfig({plugins:[vue()],server:{proxy:{'/v1':apiTarget}}})
