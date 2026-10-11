import {createApp} from 'vue'
import {createPinia} from 'pinia'
import {createRouter,createWebHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import Login from './Login.vue'
import Dashboard from './Dashboard.vue'
import {useAuth} from './stores'
const app=createApp(App),pinia=createPinia();app.use(pinia)
export const homes={STUDENT:'/student/catalog',PROFESSOR:'/professor/assignments',REGISTRAR:'/registrar/students'}
const router=createRouter({history:createWebHistory(),routes:[{path:'/login',component:Login},{path:'/:role(student|professor|registrar)/:page',component:Dashboard},{path:'/account/password',component:Dashboard},{path:'/:pathMatch(.*)*',redirect:'/login'}]})
router.beforeEach(async(to)=>{const auth=useAuth();if(!auth.user&&to.path!='/login')await auth.restore();if(!auth.user&&to.path!='/login')return '/login';if(auth.user){if(auth.user.mustChangePassword&&to.path!='/account/password')return '/account/password';if(to.path==='/login')return homes[auth.user.role];if(to.params.role&&String(to.params.role).toUpperCase()!==auth.user.role)return homes[auth.user.role]}})
window.addEventListener('auth-lost',()=>{useAuth().reset();if(router.currentRoute.value.path!='/login')router.push('/login')})
app.use(router).use(ElementPlus,{locale:zhCn}).mount('#app')
