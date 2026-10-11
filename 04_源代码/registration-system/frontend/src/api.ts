import axios from 'axios'
export type Role='STUDENT'|'PROFESSOR'|'REGISTRAR'
export interface User {id:string;username:string;role:Role;studentId:string|null;professorId:string|null;mustChangePassword:boolean}
export type Row=Record<string,any>
const http=axios.create({baseURL:'/api/v1',withCredentials:true,timeout:25000})
let csrf=''
const keys=new Map<string,string>()
export class ApiError extends Error { constructor(public code:string,message:string,public traceId:string,public status:number){super(message)} }
export async function token(){const r=await http.get('/auth/csrf');csrf=r.data.data.token;return csrf}
export function clear(){csrf='';keys.clear()}
export async function request<T=Row>(method:string,path:string,data?:unknown):Promise<T>{
  const writing=!['GET','HEAD'].includes(method),signature=JSON.stringify([method,path,data])
  if(writing&&!csrf)await token()
  if(writing&&!keys.has(signature))keys.set(signature,crypto.randomUUID())
  try{
    const r=await http.request({method,url:path,data,headers:writing?{'X-CSRF-TOKEN':csrf,'Idempotency-Key':keys.get(signature)}:{}})
    if(writing)keys.delete(signature)
    return (r.status===204?undefined:r.data.data) as T
  }catch(e:any){
    const status=e.response?.status??0,b=e.response?.data
    if(status&&writing)keys.delete(signature)
    if(status===401&&path!='/auth/login')window.dispatchEvent(new Event('auth-lost'))
    throw new ApiError(b?.code??'NETWORK_ERROR',b?.message??'网络连接中断，请重试相同操作',b?.traceId??'',status)
  }
}
export const get=<T=Row>(path:string)=>request<T>('GET',path)
export const write=<T=Row>(method:string,path:string,data?:unknown)=>request<T>(method,path,data)
