import {chromium,expect} from '../frontend/node_modules/@playwright/test/index.mjs';
import fs from 'node:fs';
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const catalog=Array.from({length:12},(_,i)=>({id:String(101+i),name:`课程${101+i}`,department:'计算机',state:'OPEN',enrolledCount:2,capacity:10,meetings:[],prerequisites:[]}));
let schedule={state:'SUBMITTED',version:7,draftItems:[{offeringId:'101',name:'课程101',choiceType:'PRIMARY',choiceOrder:1,state:'DRAFT'},{offeringId:'111',name:'课程111',choiceType:'ALTERNATE',choiceOrder:1,state:'DRAFT'}],enrolledItems:[{offeringId:'102',name:'课程102',originType:'PRIMARY',state:'ACTIVE'}],cancelledItems:[{offeringId:'103',name:'课程103',cancelReason:'LOW_ENROLLMENT'}]};
let missing=false;const writes=[],requests=[],results=[];
const context=await browser.newContext();const page=await context.newPage();
await context.route('**/api/v1/**',async route=>{
 const req=route.request(),url=new URL(req.url());requests.push(url.pathname);
 if(req.method()!=='GET'){writes.push(req.method()+' '+url.pathname);await route.abort();return;}
 let data;
 if(url.pathname.endsWith('/auth/me'))data={id:'1',username:'s1',role:'STUDENT',studentId:'1',professorId:null,mustChangePassword:false};
 else if(url.pathname.endsWith('/semesters/registration-current'))data={registrationSemesterId:'2',name:'当前学期',state:'OPEN'};
 else if(/\/catalog\/offerings\/\d+$/.test(url.pathname))data={professorName:'验证教师',meetings:[{weekday:1,startTime:'09:00:00',endTime:'10:30:00'}]};
 else if(url.pathname.includes('/catalog/offerings'))data={items:catalog,total:catalog.length};
 else if(url.pathname.includes('/student/schedules/')){if(missing){await route.fulfill({status:404,json:{code:'SCHEDULE_NOT_FOUND',message:'课表尚未创建'}});return;}data=schedule;}
 else throw new Error('Unexpected request: '+url.pathname);
 await route.fulfill({json:{data}});
});
try{
 await page.goto('http://127.0.0.1:8080/student/catalog');await expect(page.locator('.course-card')).toHaveCount(12);results.push('目录显示全部12个开课班');
 const original=JSON.stringify(schedule);
 requests.length=0;await page.getByRole('link',{name:'我的课表'}).click();await expect(page.locator('.enrolled-table .el-table__body-wrapper tbody tr')).toHaveCount(1);await expect(page.locator('.draft-table .el-table__body-wrapper tbody tr')).toHaveCount(2);await expect(page.locator('.cancelled-table .el-table__body-wrapper tbody tr')).toHaveCount(1);await expect(page.locator('.course-card')).toHaveCount(0);await expect(page.locator('.schedule-view')).not.toContainText('课程104');await expect(page.locator('.enrolled-table')).toContainText('课程102');if(requests.some(x=>x.endsWith('/catalog/offerings')))throw new Error('Schedule fetched catalog list');await expect(page.locator('.enrolled-table')).toContainText('验证教师');await expect(page.locator('.enrolled-table')).toContainText('周一 09:00–10:30');await expect(page.locator('.enrolled-table')).toContainText('未提供');results.push('课表只显示本人正式、草稿主备选及取消记录，无目录课程');
 await page.waitForTimeout(5500);await expect(page.locator('.enrolled-table .el-table__body-wrapper tbody tr')).toHaveCount(1);if(JSON.stringify(schedule)!==original||writes.length)throw new Error('Selection changed');results.push('自动刷新保持课表状态，未发送业务写请求');
 await page.getByRole('button',{name:'前往课程目录调整选课'}).click();await expect(page.locator('.course-card')).toHaveCount(12);results.push('返回目录仍显示全部课程，原选课控件保留');
 schedule={state:'DELETED',version:8,draftItems:[],enrolledItems:[],cancelledItems:[]};await page.goto('http://127.0.0.1:8080/student/schedule');await expect(page.getByText('暂无已选课程，请前往课程目录选课')).toBeVisible();results.push('空课表显示空状态');
 missing=true;await page.reload();await expect(page.getByText('暂无已选课程，请前往课程目录选课')).toBeVisible();await expect(page.locator('.el-message--error')).toHaveCount(0);results.push('未创建课表404显示空状态');
 fs.writeFileSync(new URL('../.runtime/student-schedule-validation.json',import.meta.url),JSON.stringify({time:new Date().toISOString(),mode:'Playwright browser, mocked read-only API fixtures, actual Vue pages',results,writes},null,2));console.log(JSON.stringify({passed:results.length,results,writes}));
}finally{await browser.close();}
