# FakeContactBook

Xposed / LSPosed 模块：给指定 App 或全局返回自定义假通讯录。

## 功能

- 宿主端 Activity 管理多套“通讯录方案”。
- 支持手动添加、编辑、删除联系人。
- 支持导入 VCF 文件。
- 支持按 App 开关并指定返回哪套方案。
- 支持全局方案：对所有被 Xposed 作用域命中的 App 返回同一套方案。
- Xposed 端 hook：
  - `ContentResolver.query(...)`
  - `ContentProviderClient.query(...)`
  - `READ_CONTACTS` / `WRITE_CONTACTS` / `GET_ACCOUNTS` 权限检查
  - `Activity.requestPermissions(...)` 中纯通讯录权限请求
  - ContactsProvider2 provider 模式兜底

## LSPosed 作用域建议

1. 最稳模式：勾选目标 App。本模块会在目标 App 进程内直接拦截通讯录 query，并返回 MatrixCursor 假数据。
2. 全局模式：勾选 Android/System Framework，模块会尝试在 zygote 安装全局 hook。不同 LSPosed 版本策略可能不同。
3. 兜底模式：勾选“通讯录存储 / Contacts Provider / com.android.providers.contacts”。该模式受 ROM 影响更大，而且如果 App 先做权限检查，仍建议配合客户端或全局 hook。

## 构建

用 Android Studio 打开本目录，Sync Gradle 后直接构建安装。
如果 Xposed API 依赖拉不到，确认 `settings.gradle` 里包含：

```gradle
maven { url 'https://api.xposed.info/' }
```

## 重要说明

这套代码是偏实战的第一版骨架。真实 App 查询 ContactsProvider 的 projection / selection 千差万别，当前 CursorFactory 已覆盖常见：

- ContactsContract.Contacts
- ContactsContract.RawContacts
- ContactsContract.Data
- ContactsContract.CommonDataKinds.Phone
- ContactsContract.CommonDataKinds.Email
- ContactsContract.PhoneLookup

后续可以继续按某个 App 的具体 query 日志补齐更多列。


## 这版修正

- 已移除 `de.robv.android.xposed.AndroidAppHelper` 依赖，避免部分本地 XposedBridgeAPI jar 不包含该类时编译失败。
- Android Gradle Plugin 已降到 `8.0.2`，可配合 Gradle 8.0 使用；如你想保留 AGP 8.6.1，则需要把 Gradle Wrapper 升到 8.7。
- 已增加 `gradle.properties`，包含 AndroidX 和 compileSdk 35 兼容配置。
