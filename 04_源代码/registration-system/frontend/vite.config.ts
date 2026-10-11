import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
const target=process.env.VITE_API_TARGET??'http://127.0.0.1:8081'
export default defineConfig({plugins:[vue()],server:{proxy:{'/api':{target,changeOrigin:false}}},preview:{proxy:{'/api':{target,changeOrigin:false}}}})
