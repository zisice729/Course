import {defineStore} from 'pinia'
import {ref} from 'vue'
import {get,write,token,clear,type User} from './api'
export const useAuth=defineStore('auth',()=>{
  const user=ref<User|null>(null)
  async function restore(){try{user.value=await get<User>('/auth/me')}catch{user.value=null}return user.value}
  async function login(username:string,password:string){await token();user.value=await write<User>('POST','/auth/login',{username,password});await token();return user.value}
  async function logout(){await write('POST','/auth/logout');user.value=null;clear()}
  function reset(){user.value=null;clear()}
  return {user,restore,login,logout,reset}
})
