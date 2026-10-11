<script setup lang="ts">
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {useAuth} from './stores'
const username=ref('s1'),password=ref(''),busy=ref(false),router=useRouter(),auth=useAuth()
const defaults={STUDENT:'/student/catalog',PROFESSOR:'/professor/assignments',REGISTRAR:'/registrar/students'}
async function login(){if(!username.value||!password.value){ElMessage.warning('请输入用户名和密码');return}busy.value=true;try{const u=await auth.login(username.value,password.value);password.value='';await router.push(u.mustChangePassword?'/account/password':defaults[u.role])}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}}
</script>
<template>
  <div class="login-page">
    <section class="login-story"><div class="brand"><span class="brand-symbol">W</span><span>WYLIE COLLEGE<small>学业服务平台</small></span></div><div class="story-body"><div class="eyebrow">YOUR NEXT CHAPTER</div><h1>规划每一门课，<br>开启新的学期。</h1><p>选课、授课与学业记录，在这里有序完成。</p><div class="story-lines"><span>01 <b>发现适合你的课程</b></span><span>02 <b>安排自己的学习计划</b></span><span>03 <b>查看每一步的成果</b></span></div></div><small>Wylie College · Course Registration</small></section>
    <section class="login-panel"><div class="login-card"><span class="pill">校园统一入口</span><h2>欢迎回来</h2><p class="muted">使用你的校园账号登录</p><el-form @submit.prevent="login" label-position="top"><el-form-item label="用户名"><el-input v-model="username" size="large" autocomplete="username" placeholder="请输入用户名" /></el-form-item><el-form-item label="密码"><el-input v-model="password" size="large" type="password" show-password autocomplete="current-password" @keyup.enter="login" /></el-form-item><el-button type="primary" size="large" class="login-button" :loading="busy" native-type="submit">登录学业服务平台 →</el-button></el-form><div class="demo-roles"><small>演示账号</small><button @click="username='s1'">学生 s1</button><button @click="username='p1'">教师 p1</button><button @click="username='registrar'">教务管理员</button></div><p class="login-note">首次使用临时密码登录后，请先设置自己的密码。</p></div><small class="login-footer">让学习有计划，让管理更清晰。</small></section>
  </div>
</template>
