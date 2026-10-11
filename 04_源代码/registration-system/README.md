# 高校选课与成绩管理系统

## 快速开始（推荐首次验收阅读）

本项目是高校选课与成绩管理系统，提供学生选课及课表查询、教师授课及成绩管理、教务管理员注册管理等功能。首次运行可按照以下步骤操作，详细环境和运行说明保留在后文。

1. 准备完整JDK 21及Python 3.10+，确认`python`命令可用；首次安装和构建需要网络。打开PowerShell，进入`04_源代码/registration-system`源码目录：

   ```powershell
   # 将示例路径替换为本机实际保存位置。
   cd 'D:\_MY_大三下\软工课设\软件工程课程设计_最终提交版\04_源代码\registration-system'
   ```

2. 安装项目所需工具，然后构建并启动系统：

   ```powershell
   python scripts/bootstrap_tools.py
   powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Build
   ```

   等待终端完成启动；如果出现错误，请先查看下方“常见问题”和后文详细说明。

3. 在源码目录查看演示账号：

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts/show_demo_access.ps1
   ```

   该命令会在本机终端显示演示账号和随机初始化密码。首次登录按页面提示修改密码；已改密的账号使用修改后的密码，该命令不会重置密码。

4. 浏览器访问 **http://127.0.0.1:8080**，使用上一步查看的学生、教师或管理员账号登录。

## 停止系统

验收结束后，在同一个源码目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop.ps1 -IncludeDatabase
```

该命令停止本项目管理的前端服务、后端服务和MySQL实例，以及随项目启动的模拟服务；不会删除数据库中的业务数据。

## 常见问题

### 问题1：启动后浏览器无法访问

检查启动终端是否存在错误，并确认8080端口未被其他程序占用。确认系统已启动后访问`http://127.0.0.1:8080`；端口检查方法见后文“默认端口与冲突处理”。

### 问题2：出现JAVA_HOME相关错误

当前启动脚本会自动识别有效JDK 21：优先使用有效的JAVA_HOME，否则从PATH查找Java。如果仍然失败，请检查本机是否安装完整JDK 21及其环境设置，具体检查和配置方法见后文“进入源码目录并检查环境”。

### 问题3：如何获取账号密码

不要从README中寻找固定密码。在源码目录执行以下命令，查看本机生成的随机初始化密码：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/show_demo_access.ps1
```

需要先完成首次启动，使本机初始化凭据生成。已修改密码的账号使用本人设置的新密码；请勿公开终端显示的凭据。

按照以下步骤运行。本说明列出当前交付配置，不表示已经在新目录或老师电脑上验证成功。

## 1. 环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows x64；Windows PowerShell；首次安装可访问工具分发及依赖仓库 |
| JDK | 完整JDK 21；有效JAVA_HOME优先，否则自动从PATH中的java.exe识别JDK |
| Maven | 3.9.11；工具安装脚本安装到项目.tools，无需预先全局安装 |
| Node.js | 22.23.3；工具安装脚本安装到项目.tools；前端依赖由锁文件安装 |
| Python | 3.10+，python命令在PATH可用；模拟服务采用标准库 |
| MySQL | 8.4.11；工具安装脚本准备独立实例，默认端口3307 |
| 其他 | curl.exe可用；PowerShell可创建项目所需目录别名；首次构建需要网络 |

## 2. 进入源码目录并检查环境

如果文件保存在当前课程工作目录，打开PowerShell后执行：

```powershell
Set-Location -LiteralPath 'D:\_MY_大三下\软工课设\软件工程课程设计_最终提交版\04_源代码\registration-system'
java -version
javac -version
python --version
curl.exe --version
```

复制到其他电脑后，第一条命令替换为实际源码目录。启动脚本优先验证JAVA_HOME；未设置或无效时尝试PATH中的java.exe，并依据Java返回的java.home找到完整JDK 21。只有JRE、版本不符或完全未找到Java时会给出安装/配置提示。检测在数据库初始化之前执行；只设置本次PowerShell及子进程的JAVA_HOME和PATH，不修改系统或用户永久环境变量。

如果PATH也没有JDK，可先配置本机真实安装位置后再启动：

```powershell
# 路径仅为示例，须替换为本机真实JDK 21目录，不填bin或java.exe。
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
```

## 3. 安装工具并启动

```powershell
python scripts/bootstrap_tools.py
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Build
powershell -ExecutionPolicy Bypass -File scripts/show_demo_access.ps1
```

工具脚本下载并校验项目所需Maven、Node和MySQL。启动脚本使用便携工具，安装前端依赖、构建后端，并在本副本首次运行时生成数据库和随机凭据。无需在README中填写DB_PASSWORD或MOCK_TOKEN；启动脚本从本副本.runtime读取它们，不复制其他项目凭据。

浏览器访问 **http://127.0.0.1:8080**。学生账号s1、教师p1、教务管理员registrar；随机初始密码通过第三条命令在本机查看，并按页面要求修改。不要将凭据放入公开文件或截图。

## 4. 默认端口与冲突处理

当前默认端口：网页8080、后端8081、MySQL3307、目录服务8091、计费8092。隔离运行使用网页8088、后端8082、计费8094。**3306仅是MySQL常见默认端口，不属于本交付基线。**

先查看端口占用，确认占用进程所属项目：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3307,8080,8081,8082,8088,8091,8092,8094 -ErrorAction SilentlyContinue |
    Select-Object LocalPort,OwningProcess
```

如果原课程目录的服务仍在运行，可从那个项目目录执行其scripts/stop.ps1 -IncludeDatabase；其他软件使用端口时，通过其正常停止方式处理。不要按端口号盲目终止进程。停止原项目服务只影响进程，本说明不要求修改原文件。交付启动脚本会拒绝复用其他目录的服务。

本副本已初始化后，停止本副本服务可执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop.ps1 -IncludeDatabase
```

## 5. 操作和隔离运行

演示业务日期固定为2026-10-08 10:00（上海）；初始数据为30名学生、3名教师、12个当前班及6个历史班。学生先保存4主选2备选草稿再正式提交；教师查看本人名单和历史成绩；管理员最后关闭注册。关闭后选课和授课冻结。

需要反复完成关闭流程时使用隔离库，先备份再重置：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/rehearse.ps1 -ResetFixture
```

访问http://127.0.0.1:8088。该流程使用registration_test，不用于清理普通演示库registration。停止并重新构建前须停止后端，避免Windows占用运行中的JAR。

目录接口需求上限10秒，代码请求预算9秒，连接预算2秒包含在请求预算内；计费发送持续重试，等待确认不代表已送达。

完整环境、备份及操作流程见[运行与部署说明](../../05_运行与部署说明/运行与部署说明_V2.0.docx)。实际测试结果和未完成范围见[测试报告](../../03_测试计划与测试用例/测试报告_V0.2.0.docx)。当前测试台账保留PARTIAL/NOT_RUN，未完成的正式性能和长期可用性不得视为通过。
