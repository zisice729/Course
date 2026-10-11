import {chromium,expect} from '../frontend/node_modules/@playwright/test/index.mjs';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const secret=JSON.parse(fs.readFileSync(path.join(root,'.runtime/secrets.json'),'utf8').replace(/^\uFEFF/,''));
const out=path.join(root,'tests/results');fs.mkdirSync(out,{recursive:true});
const screenshots=path.join(out,'screenshots');fs.mkdirSync(screenshots,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const results=[],errors=[];
async function check(title,fn){const start=Date.now();try{await fn();results.push({title,status:'PASS',durationMs:Date.now()-start});console.log('PASS',title)}catch(e){results.push({title,status:'FAIL',error:String(e)});throw e}finally{fs.writeFileSync(path.join(out,'browser.json'),JSON.stringify({executedAt:new Date().toISOString(),results,pageErrors:errors},null,2))}}
async function login(username){
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage();
 page.on('pageerror',e=>errors.push(String(e)));
 await page.goto('http://127.0.0.1:8080/login');
 await page.locator('input[autocomplete="username"]').fill(username);
 await page.locator('input[autocomplete="current-password"]').fill(secret.demoPassword);
 await page.getByRole('button',{name:'登录学业服务平台'}).click();
 await expect(page).toHaveURL(/\/(student|professor|registrar)\//);await expect(page.locator('.user-chip')).toContainText(username);await expect(page.locator('.el-loading-mask')).toHaveCount(0);
 return {context,page};
}
try{
 let student;
 await check('学生浏览器登录、远程搜索与课程目录',async()=>{student=await login('s1');await expect(student.page.locator('.course-card')).toHaveCount(12);await student.page.getByPlaceholder('搜索课程名称或班号').fill('数据库');await expect(student.page.locator('.course-card')).toHaveCount(1);await student.page.getByPlaceholder('搜索课程名称或班号').fill('');await expect(student.page.locator('.course-card')).toHaveCount(12);await student.page.screenshot({path:path.join(screenshots,'student-catalog.png'),fullPage:true})});
 const p=student.page;
 await check('浏览器保存4+2草稿，刷新后恢复',async()=>{
  const existing=await p.evaluate(async()=>{let r=await fetch('/api/v1/student/schedules/2');return (await r.json()).data});
  if(existing.draftItems.length===0){for(const id of ['101','102','103','104'])await p.locator('.course-card').filter({hasText:`班号 ${id}`}).getByRole('button',{name:'加入主选',exact:true}).click();for(const id of ['105','106'])await p.locator('.course-card').filter({hasText:`班号 ${id}`}).getByRole('button',{name:'加入备选 +',exact:true}).click();}
  await p.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(p.getByText('草稿已保存，正式课表不变',{exact:true})).toBeVisible();await p.reload();await expect(p.locator('.choice-row')).toHaveCount(6);
 });
 await check('浏览器正式提交，草稿调整不修改正式课表',async()=>{
  await p.getByRole('button',{name:'正式提交',exact:true}).click();await expect(p.getByText('正式课表已更新',{exact:true})).toBeVisible();await expect(p.locator('.formal-list>div')).toHaveCount(4);
  const removedId=(await p.locator('.choice-row:not(.alternate) small').first().innerText()).match(/班号 (\d+)/)[1];
  await p.getByRole('button',{name:'移除课程',exact:true}).first().click();await p.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(p.getByText('草稿已保存，正式课表不变',{exact:true})).toBeVisible();await expect(p.locator('.formal-list>div')).toHaveCount(4);
  await p.locator('.course-card').filter({hasText:'班号 '+removedId}).getByRole('button',{name:'加入主选',exact:true}).click();await p.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(p.locator('.choice-row')).toHaveCount(6);
 });
 await check('学生成绩页面与服务器端角色拒绝',async()=>{
  await p.getByRole('link',{name:'成绩查询'}).click();await expect(p.getByText('上一已完成学期成绩',{exact:true})).toBeVisible();await expect(p.locator('.el-table__body-wrapper tbody tr')).toHaveCount(2);
  expect(await p.evaluate(async()=>{let r=await fetch('/api/v1/registrar/students');return r.status})).toBe(403);
  await p.screenshot({path:path.join(screenshots,'student-grades.png'),fullPage:true});
 });
 await student.context.close();
 let teacher;
 await check('教师授课页面、名单与成绩页面',async()=>{
  teacher=await login('p1');await expect(teacher.page.locator('.teacher-grid .course-card')).toHaveCount(12);await teacher.page.screenshot({path:path.join(screenshots,'teacher-assignments.png'),fullPage:true});
  await teacher.page.getByRole('link',{name:'名单与成绩'}).click();await expect(teacher.page.locator('.el-table__body-wrapper tbody tr')).toHaveCount(10);await teacher.page.screenshot({path:path.join(screenshots,'teacher-grades.png'),fullPage:true});
 });
 await teacher.context.close();let admin;
 await check('教务人员分页、SSN脱敏与搜索',async()=>{
  admin=await login('registrar');await expect(admin.page.locator('.el-table__body-wrapper tbody tr')).toHaveCount(10);await expect(admin.page.locator('.el-table__body-wrapper')).toContainText('***');
  await admin.page.getByPlaceholder('按姓名或ID搜索').fill('林同学');await admin.page.getByRole('button',{name:'搜索',exact:true}).click();await expect(admin.page.locator('.el-table__body-wrapper tbody tr')).toHaveCount(1);
  await admin.page.screenshot({path:path.join(screenshots,'registrar-people.png'),fullPage:true});
 });
 await check('关闭和计费页面、刷新路由、注销',async()=>{
  await admin.page.getByRole('link',{name:'注册关闭'}).click();await expect(admin.page.getByRole('button',{name:'关闭注册',exact:true})).toBeVisible();await admin.page.screenshot({path:path.join(screenshots,'registrar-close.png'),fullPage:true});
  await admin.page.getByRole('link',{name:'计费状态'}).click();await expect(admin.page.getByText('注册关闭后将生成最终账单',{exact:true})).toBeVisible();await admin.page.reload();await expect(admin.page.locator('h1')).toHaveText('计费状态');
  await admin.page.getByRole('button',{name:'退出登录'}).click();await expect(admin.page.locator('h2')).toHaveText('欢迎回来');expect(await admin.page.evaluate(async()=>{const r=await fetch('/api/v1/auth/me');return r.status})).toBe(401);
 });
 await admin.context.close();
 await check('浏览器无未捕获脚本异常',async()=>expect(errors).toEqual([]));
}finally{await browser.close()}
