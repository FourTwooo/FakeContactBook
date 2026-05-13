# FakeContactBook

FakeContactBook 是一个面向 Android / Xposed 的假通讯录管理工具。项目目标是让指定 App 或全局规则在读取系统通讯录时拿到可控的假通讯录数据，从而降低真实联系人被读取、同步、上传的风险。

---

## 功能概览

- **通讯录方案管理**
  - 创建多个通讯录方案
  - 编辑联系人姓名、手机号、邮箱
  - VCF 导入
  - 方案删除后自动处理关联规则

- **指定 App 规则**
  - 给每个 App 单独开启 / 关闭假通讯录
  - 每个 App 可绑定不同通讯录方案
  - 指定 App 规则优先级高于全局规则

- **全局 Hook**
  - 开启后，对非白名单 App 应用兜底隐私策略
  - 当前 Provider Hook 代码中，全局规则默认返回空通讯录
  - 真实通讯录白名单中的 App 默认放行真实通讯录
  - 如果给白名单 App 显式开启规则，则显式规则优先

- **Provider Hook 状态检测**
  - 首页可检测 `com.android.providers.contacts` 是否已经被 Xposed 挂载
  - 勾选 `com.android.providers.contacts` 后通常需要重启手机或重启 `android.process.acore`

- **第三方社交资料归档**
  - 被 Hook App 或外部工具可通过 ContentProvider / HTTP API 提交社交资料
  - 按 `packageName + phone` 归档
  - 支持昵称、账号、头像 Base64、头像 URL 等自由字段
  - 同一平台多次提交会合并字段，不应整包覆盖旧数据

- **手机号生成器**
  - 支持手机号模板生成，例如 `1380013****`
  - 支持运营商筛选
  - 支持本地号段数据库
  - 生成结果可追加到现有方案、新建方案并导入、导出 VCF
  - 新建方案导入支持按数量分批

- **通讯录查询测试器**
  - 用于在宿主 App 内测试 Provider Hook 结果
  - 可模拟某个包名读取通讯录
  - 可验证全局规则、指定 App 规则、白名单放行是否符合预期

- **HTTP API**
  - 默认端口：`9420`
  - 默认绑定：`127.0.0.1`
  - 支持方案、联系人、规则、全局配置、社交资料、手机号生成等接口
  - 页面内提供接口文档和测试请求入口

---

## 运行环境

- Android
- LSPosed / Xposed 环境
- 作用域：`com.android.providers.contacts`

---

## Xposed Hook 策略

当前 Provider Hook 的优先级为：

```text
1. 显式 App 规则
   命中后返回绑定方案里的假联系人。

2. 真实通讯录白名单
   没有显式规则时，放行真实通讯录。

3. 全局规则
   当前代码默认返回空通讯录。
```

---

## 安装与初始化

1. 构建并安装 APK。
2. 打开 LSPosed。
3. 给模块勾选作用域：
   ```text
   com.android.providers.contacts
   ```
4. 重启手机。
5. 打开 FakeContactBook。
6. 首页查看 Provider Hook 状态。
7. 创建通讯录方案。
8. 设置全局 Hook 或指定 App 规则。
9. 用“通讯录查询测试器”验证返回结果。

---

## 社交资料

第三方社交资料按 `packageName + phone` 写入联系人。

示例：

```json
{
  "packageName": "com.tencent.mobileqq",
  "phone": "13800138000",
  "nickname": "张三的 QQ",
  "account": "10001",
  "avatarBase64": "",
  "avatarUrl": "https://example.com/avatar.png"
}
```

字段说明：

- `packageName`：平台 App 包名，必填
- `phone`：手机号，必填，用于匹配联系人
- 其他字段自由扩展
- 常见字段：
  - `nickname`
  - `account`
  - `avatarBase64`
  - `avatarUrl`
  - `uid`
  - `openId`
  - `gender`

在编辑通讯录方案页面中，社交资料可展示平台来源、昵称、账号、头像等信息。头像字段不会重复显示原始 Base64 文本；头像可用于图片识别搜索。

---

## 手机号生成器

入口：

```text
扩展能力 -> 手机号生成器
```

支持输入：

- 手机号模板
- 城市名
- 运营商
- 最大生成数量
- 是否使用本地号段数据库

模板示例：

```text
1380013****
```

生成结果可执行：

```text
追加到现有方案
新建方案并导入
导出 VCF 文件
```

新建方案并导入支持分批。例如生成 5000 个手机号，每个方案最多 2000 个，基础名为“手机号表”，会创建：

```text
手机号表
手机号表_1
手机号表_2
```

---

## HTTP API

入口：

```text
扩展能力 -> HTTP API
```

默认地址：

```text
http://127.0.0.1:9420
```

默认端口：

```text
9420
```

默认只监听本机地址 `127.0.0.1`，避免直接暴露到局域网。

### 启动与测试

1. 打开 HTTP API 页面。
2. 设置端口，默认 `9420`。
3. 点击启动。
4. 在页面内选择接口并测试。
5. 也可以通过 curl 或其他工具访问。

### 基础接口

#### GET /

获取接口文档。

```bash
curl http://127.0.0.1:9420/
```

#### GET /health

检测服务状态。

```bash
curl http://127.0.0.1:9420/health
```

#### GET /stats

获取统计信息。

```bash
curl http://127.0.0.1:9420/stats
```

响应包含：

```text
profileCount
contactCount
enabledRuleCount
socialProfileRecordCount
global
```

---

## HTTP API：通讯录方案

### GET /profiles

获取所有通讯录方案。

```bash
curl http://127.0.0.1:9420/profiles
```

### POST /profiles

创建方案。

```bash
curl -X POST http://127.0.0.1:9420/profiles \
  -H "Content-Type: application/json" \
  -d '{"name":"API测试方案"}'
```

### GET /profiles/{profileId}

获取指定方案。

```bash
curl http://127.0.0.1:9420/profiles/<profileId>
```

### PUT /profiles/{profileId}

修改方案名称。

```bash
curl -X PUT http://127.0.0.1:9420/profiles/<profileId> \
  -H "Content-Type: application/json" \
  -d '{"name":"修改后的方案名"}'
```

### DELETE /profiles/{profileId}

删除方案。

```bash
curl -X DELETE http://127.0.0.1:9420/profiles/<profileId>
```

---

## HTTP API：联系人

### GET /profiles/{profileId}/contacts

获取方案联系人。

```bash
curl http://127.0.0.1:9420/profiles/<profileId>/contacts
```

### POST /profiles/{profileId}/contacts

新增联系人。

单个联系人：

```bash
curl -X POST http://127.0.0.1:9420/profiles/<profileId>/contacts \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","phone":"13800138000","email":"zhangsan@example.com"}'
```

批量联系人：

```bash
curl -X POST http://127.0.0.1:9420/profiles/<profileId>/contacts \
  -H "Content-Type: application/json" \
  -d '{
    "contacts": [
      {"name":"张三","phone":"13800138000"},
      {"name":"李四","phone":"13900139000"}
    ]
  }'
```

### PUT /profiles/{profileId}/contacts/{contactId}

修改联系人。

```bash
curl -X PUT http://127.0.0.1:9420/profiles/<profileId>/contacts/<contactId> \
  -H "Content-Type: application/json" \
  -d '{"name":"王五","phone":"13700137000","email":"wangwu@example.com"}'
```

### DELETE /profiles/{profileId}/contacts/{contactId}

删除联系人。

```bash
curl -X DELETE http://127.0.0.1:9420/profiles/<profileId>/contacts/<contactId>
```

---

## HTTP API：App 规则

### GET /rules

获取所有规则。

```bash
curl http://127.0.0.1:9420/rules
```

### GET /rules/{packageName}

获取指定 App 规则。

```bash
curl http://127.0.0.1:9420/rules/com.example.app
```

### PUT /rules/{packageName}

设置指定 App 规则。

```bash
curl -X PUT http://127.0.0.1:9420/rules/com.example.app \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"profileId":"<profileId>"}'
```

### DELETE /rules/{packageName}

删除指定 App 规则。

```bash
curl -X DELETE http://127.0.0.1:9420/rules/com.example.app
```

---

## HTTP API：全局配置

### GET /global

获取全局 Hook 配置。

```bash
curl http://127.0.0.1:9420/global
```

### PUT /global

设置全局 Hook 配置。

```bash
curl -X PUT http://127.0.0.1:9420/global \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"profileId":"<profileId>"}'
```

---

## HTTP API：社交资料

### POST /social/upsert

提交社交资料。

```bash
curl -X POST http://127.0.0.1:9420/social/upsert \
  -H "Content-Type: application/json" \
  -d '{
    "packageName": "com.tencent.mobileqq",
    "phone": "13800138000",
    "nickname": "测试昵称",
    "account": "10001",
    "avatarBase64": ""
  }'
```

响应字段：

```text
ok
reason
packageName
phone
normalizedPhone
matchedCount
resultJson
```

---

## HTTP API：手机号生成

### GET /generator/phones

```bash
curl "http://127.0.0.1:9420/generator/phones?incompletePhone=1380013****&maxResults=20&isp=不限"
```

### POST /generator/phones

```bash
curl -X POST http://127.0.0.1:9420/generator/phones \
  -H "Content-Type: application/json" \
  -d '{
    "incompletePhone": "1380013****",
    "cityName": "",
    "isp": "不限",
    "maxResults": 20,
    "useDb": false
  }'
```

---

## Content Provider API

宿主提供 ContentProvider，用于 Xposed Provider Hook 读取配置，也用于第三方平台资料提交。

基础 Authority：

```text
com.fourtwo.fakecontactbook.provider
```

配置查询：

```text
content://com.fourtwo.fakecontactbook.provider/config?pkg=<packageName>
```

社交资料提交：

```text
content://com.fourtwo.fakecontactbook.provider/social
```

方法：

```text
upsertSocialProfile
```

Bundle 字段：

```text
payloadJson
```

payload 示例：

```json
{
  "packageName": "com.tencent.mobileqq",
  "phone": "13800138000",
  "nickname": "张三",
  "account": "10001"
}
```

---

## 通讯录查询测试器

入口：

```text
扩展能力 -> 通讯录查询测试器
```

用途：

- 验证 Provider Hook 是否生效
- 模拟某个包名读取通讯录
- 对比全局规则、指定 App 规则、白名单放行结果

示例测试：

```text
测试包名：com.eg.android.AlipayGphone
查询类型：手机号列表
```

预期：

- 如果该 App 有显式规则：返回假联系人
- 如果全局开启但没有显式规则：当前代码返回空通讯录
- 如果全局关闭且没有显式规则：返回真实通讯录
- 如果测试包名在真实通讯录白名单中：返回真实通讯录，除非它被显式规则覆盖

---

## 常见问题

### 首页显示 Provider Hook 未开启

确认 LSPosed 作用域已勾选：

```text
com.android.providers.contacts
```

然后重启手机或重启 `android.process.acore`。

### 指定 App 没有返回假联系人

检查：

1. Provider Hook 状态是否已开启
2. App 规则是否启用
3. 规则绑定的 profileId 是否存在
4. 目标 App 是否缓存了旧通讯录结果
5. 是否需要清除目标 App 缓存或重新触发通讯录同步

---

## 免责声明

本项目用于隐私保护、调试和研究用途。请勿用于违反法律法规、绕过合法审计或破坏第三方服务的场景。使用 Xposed / LSPosed 修改系统行为具有风险，安装前请自行评估设备稳定性和数据备份。
