import {chromium,expect} from '../frontend/node_modules/@playwright/test/index.mjs';
import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url))),out=path.join(root,'tests/results');
const secret=JSON.parse(fs.readFileSync(path.join(root,'.runtime/secrets.json'),'utf8').replace(/^\uFEFF/,''));
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true}),rows=[],errors=[];
const shots=path.join(out,'rehearsal-screenshots');fs.mkdirSync(shots,{recursive:true});
async function check(id,title,refs,fn){try{await fn();rows.push({scenarioId:id,title,relatedCaseIds:refs,status:'PASS'});console.log(id,'PASS')}catch(e){rows.push({scenarioId:id,title,relatedCaseIds:refs,status:'FAIL',error:String(e)});throw e}finally{fs.writeFileSync(path.join(out,'rehearsal.json'),JSON.stringify({executedAt:new Date().toISOString(),base:'http://127.0.0.1:8088',database:'registration_test',executor:'Codex browser automation; group manual acceptance remains pending',results:rows,pageErrors:errors},null,2))}}
async function login(user,base='http://127.0.0.1:8088'){
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)));
 await page.goto(base+'/login');await page.locator('input[autocomplete="username"]').fill(user);await page.locator('input[autocomplete="current-password"]').fill(secret.demoPassword);await page.getByRole('button',{name:'登录学业服务平台'}).click();await expect(page).toHaveURL(/\/(student|professor|registrar)\//);await expect(page.locator('.user-chip')).toContainText(user);await expect(page.locator('.el-loading-mask')).toHaveCount(0);return {context,page};
}
let student,teacher,admin;
try{
 await check('B01','学生从页面完成首次4+2提交',['TC-REG-003','TC-UI-002'],async()=>{
  student=await login('s1');let p=student.page;
  for(const id of ['101','102','103','104'])await p.locator('.course-card').filter({hasText:'班号 '+id}).getByRole('button',{name:'加入主选',exact:true}).click();
  for(const id of ['105','106'])await p.locator('.course-card').filter({hasText:'班号 '+id}).getByRole('button',{name:'加入备选 +',exact:true}).click();
  await p.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(p.getByText('草稿已保存，正式课表不变',{exact:true})).toBeVisible();await expect(p.locator('.formal-list>div')).toHaveCount(0);
  await p.getByRole('button',{name:'正式提交',exact:true}).click();await expect(p.getByText('正式课表已更新',{exact:true})).toBeVisible();await expect(p.locator('.formal-list>div')).toHaveCount(4);
 });
 await check('B02','教师成绩页面保存及切换前未保存保护',['TC-GRADE-001','TC-UI-007'],async()=>{
  teacher=await login('p1');let p=teacher.page;await p.getByRole('link',{name:'名单与成绩'}).click();await expect(p.locator('.el-table__body-wrapper tbody tr')).toHaveCount(10);
  await p.locator('.el-table__body-wrapper tbody tr').first().locator('.el-select').click();await p.getByRole('option',{name:'B',exact:true}).click();
  await p.locator('.panel>.el-select').click();await p.getByRole('option',{name:'离散数学 · 202',exact:true}).click();await expect(p.getByText('成绩修改尚未保存，确认切换班级？',{exact:true})).toBeVisible();await p.getByRole('button',{name:'取消',exact:true}).click();
  await p.getByRole('button',{name:'保存成绩',exact:true}).click();await expect(p.getByText('成绩已保存；留空不会清除已有成绩',{exact:true})).toBeVisible();await expect(p.locator('.el-table__body-wrapper tbody tr').first()).toContainText('B');
 });
 await check('B03','教务新增人员及按行重置密码',['TC-PEOPLE-001','TC-PEOPLE-013','TC-UI-006'],async()=>{
  admin=await login('registrar');let p=admin.page;await p.getByRole('button',{name:'新增学生',exact:true}).click();let d=p.getByRole('dialog',{name:'新增人员与账号'});await d.locator('input').first().fill('浏览器验收新增');await d.getByPlaceholder('9位数字，可使用XXX-XX-XXXX').fill('234567891');await d.getByRole('button',{name:'保存',exact:true}).click();
  let credential=p.getByRole('dialog',{name:'一次性账号凭据'});await expect(credential).toBeVisible();let old=await credential.locator('.credential-value').innerText();expect(old.length).toBeGreaterThanOrEqual(12);await credential.getByRole('button',{name:'我已保存'}).click();
  await p.getByPlaceholder('按姓名或ID搜索').fill('浏览器验收新增');await p.getByRole('button',{name:'搜索',exact:true}).click();await expect(p.locator('.el-table__body-wrapper tbody tr')).toHaveCount(1);
  await p.getByRole('button',{name:'重置密码',exact:true}).click();await p.getByRole('button',{name:'确定',exact:true}).click();await expect(credential).toBeVisible();let next=await credential.locator('.credential-value').innerText();expect(next).not.toBe(old);await credential.getByRole('button',{name:'我已保存'}).click();old='';next='';
 });
 await check('B04','页面关闭注册直至全部计费回执确认',['TC-CLOSE-001','TC-UI-009','TC-UI-005'],async()=>{
  const p=admin.page;await p.getByRole('link',{name:'注册关闭'}).click();await p.getByRole('button',{name:'关闭注册',exact:true}).click();await p.getByRole('button',{name:'确认关闭',exact:true}).click();await expect(p.locator('.job-summary h3')).toHaveText('全部完成',{timeout:45000});await p.screenshot({path:path.join(shots,'close-complete.png'),fullPage:true});
  await p.getByRole('link',{name:'计费状态'}).click();await expect(p.locator('.el-table__body-wrapper tbody tr')).toHaveCount(12);await expect(p.locator('.el-table__body-wrapper')).not.toContainText('待发送');await expect(p.locator('.el-table__body-wrapper')).toContainText('已确认');await p.screenshot({path:path.join(shots,'billing-complete.png'),fullPage:true});
 });
 await check('B05','关闭后的学生页面只读及成绩查询',['TC-REG-014','TC-GRADE-006'],async()=>{
  const p=student.page;await p.reload();await expect(p.getByRole('button',{name:'正式提交',exact:true})).toBeDisabled();await expect(p.locator('.formal-list>div')).toHaveCount(4);await p.screenshot({path:path.join(shots,'student-final.png'),fullPage:true});await p.getByRole('link',{name:'成绩查询'}).click();await expect(p.locator('.grade-badge').first()).toHaveText('B');
 });
 await check('B06','正常演示库仍OPEN且无脚本异常',['TC-CI-003'],async()=>{
  const main=await login('s1','http://127.0.0.1:8080');expect(await main.page.evaluate(async()=>{return (await (await fetch('/api/v1/semesters/registration-current')).json()).data.state})).toBe('OPEN');await main.context.close();expect(errors).toEqual([]);
 });
}finally{await browser.close()}
