# AVstar ↔ 上游 webhtv 同步规程

> 2026-11-13 换基重放完成后生效。之前两条历史无共同祖先（拷贝建仓），从此以后可以正常 git merge。

## 底座与嫁接点

| 项 | 值 |
|---|---|
| 本次重放底座（旧） | `v5.6.0-beta-202609191357`（2026-09-19 tag） |
| 本次嫁接到的上游提交 | `852d8428d`（upstream/main，2026-11-08 merge，≈11-12 beta 内容） |
| 重放提交 | `1010b21a1`（step1 整搬）+ `bc7ca5fc5`（step2 手工合并） |
| 上游 remote | `upstream` → `https://github.com/Silent1566/webhtv`（fetch/push 走代理 7890） |
| 包名/品牌 | `com.avstar.tv.juchang` / 星秀（app/build.gradle 写死，无 -Pbrand） |

## 五类本地差异（相对上游）

1. **光圈导航**：`NavOrbView`（mobile/custom/）+ `fragment_home.xml` 用 `navOrb`（上游是标准 `navigation` 底栏）。上游的 `R.id.following` 项已在 `menu_nav.xml`，HomeActivity 已适配（显隐/点击/调度），徽章 API 光圈暂无、未接。
2. **收藏分组**：Keep 表 `group` 列（DB v49）+ `KeepGroupsDialog` + KeepActivity 搜索/分组 tab。**上游 Following 追更系统是另一套**，两者并存：Following 项也进了光圈菜单（默认关）。
3. **mihomo 代理**：`proxy/MihomoManager`、`ProxyNode`、`ProxySubscriptionManager`、`ProxySubscriptionDialog`、`libmihomo.so`、`jvmili` 等。上游没有 mihomo，但有自己的一套 Config.interfaceKey 身份体系（DB 45→46），两者字段同名但来源不同——已在 48→49 迁移里防御性兼容。
4. **图集/磁力/LUT**：`player/Source`（磁力/图集后台下载，PlaybackActivity 退出时 pause）、reader.html 图集 UI（comicExitFloat）、`lut_presets/*.cube`、lab 的 webview Dialog。
5. **品牌/构建**：build.gradle 品牌、图标 mipmaps、`build_one.ps1`（代理现在是 7890）、PORTING.md/HANDOFF.md。

## 数据库版本对齐（重要）

- 上游链：…→45→46（Config.interfaceKey/urlsJson）→47（History.sourceBindingKey）→48（identity clues）→**49（AVstar：Keep.group + 防御性补 Config 列）**
- 旧 AVstar 46 库（只有 Keep.group、Config 缺 interfaceKey）升级路径：46→47→48→49 均为 addColumnIfMissing，结构收敛一致，数据不丢。
- schema JSON 由编译时 Room 自动导出到 `app/schemas/`，VERSION 改动后记得提交新 json。

## 下次同步上游的标准流程

```
$env:HTTPS_PROXY='http://127.0.0.1:7890'
git fetch upstream
git merge upstream/main          # 现在有共同祖先，可以直接 merge
# 冲突原则：以上游新结构为底，把五类差异重新嵌回去（参照上表）
# DB：上游若又加了迁移，把我们的 MIGRATION_48_49 重编号到最新之后，VERSION 递增
.\build_one.ps1
```

- 我们这一侧的 delta 随时可看：`git diff upstream/main...main`（三点）。
- 编译产物：`app\build\outputs\apk\mobileArm64_v8a\release\mobile-arm64_v8a.apk`。


---

## TLS 指纹桥（libtlsclient.so，2026-11-14 新增）

独立 Go 小内核（`tlsclient/`，与 libmihomo.so 同一打包/exec 模式），给 py/js 源提供浏览器 TLS 指纹仿真出站。

### 架构

```
py/js 源 → /webResource?url=..&imp=chrome_131 (壳内 9978 端口)
              ├─ imp 有值  → TlsClientManager.ensure() 拉起 Go 桥 → 转发（浏览器指纹档）
              ├─ gw=1 无imp → OkHttp（网关档，Conscrypt HTTP/2）
              └─ 都没有    → requests 直连（原始档）
```

- Go 桥监听 127.0.0.1:17890-17899（逐端口试，生效端口写 `files/tlsclient_port`）
- `app/src/main/java/com/fongmi/android/tv/tls/TlsClientManager.java`：懒启动/孤儿复用/端口挑选/进程管理
- py 基座 `chaquo/src/main/python/base/spider.py`：`fetch/post` 支持 `ext {"imp":"chrome_131"}` 与全局 `py_impersonate`；502 自动回落 OkHttp 网关 → requests

### py 源用法（不用知道桥端口）

```python
# 站点 ext 填 {"imp":"chrome_131"}（或全局 Prefers py_impersonate）
# 源代码里 self.fetch(...)/self.post(...) 自动走浏览器指纹档
```

### 重新编译内核

```
powershell -NoProfile -ExecutionPolicy Bypass -File tlsclient/build.ps1
# 产物自动写入 app/src/main/jniLibs/arm64-v8a/libtlsclient.so（仅 arm64-v8a）
# go 工具链 D:\Go（1.22+），模块下载走 goproxy.cn；协议见 tlsclient/main.go 头注释
```

---

## WvProxy 与播放直链捕获（WvCaptureClient，2026-11-14 新增）

针对 Cloudflare 风控站（supjav 等）把「过盾 / 取页 / 取播放直链」全放进真机
WebView，绕开桥的 TLS 指纹封禁（CF error 1010）。两个新类都在
`app/src/main/java/com/fongmi/android/tv/web/`。

### WvProxy（进程内 WebView 代理覆盖，免权限）
- androidx.webkit `ProxyController` 封装：`set(proxyAddr, Executor, Runnable)` /
  `clear(Executor, Runnable)` / `supported()`；120 秒自动 clear 兜底。
- py 侧用法：`W.set('127.0.0.1:%d' % port)` → 过盾/取页窗口内把 WebView 流量
  打进 mihomo，`W.clear()` 解除。cookie 有效时静默无弹窗取页，失败再弹可视窗。

### WvCaptureClient（播放直链捕获）
- 真继承 `WebViewClient`，`shouldInterceptRequest` 旁路观察 `.m3u8/.mp4`，命中经
  `OnHit` 接口回调 py。
- **为什么放壳子**：Chaquopy 的 `dynamic_proxy` 只能代理接口，无法继承 Java
  类（`class _Cap(WebViewClient)` 抛 `TypeError`）。故捕获逻辑在壳子，py 只
  实现 `OnHit`。
- 调试期把页面生命周期与全部请求打进 `SpiderDebug`（tag=`wv-cap`）。

### py 源捕获流程（supjav_CF1.py `_wv_capture`）
1. s1 解析页（`lk1.supremejav.com/supjav.php?l=`）有 `top==location` 检查，
   **仅被 iframe 嵌入时才 `start_player()`** → 用 `loadDataWithBaseURL` 套一层
   包装页把它嵌进 iframe（`loadUrl(url, dict)` 的 dict 不被 Chaquopy 转 Map，
   需显式 `java.util.HashMap`，或干脆用包装页）。
2. iframe 内 `?c=` 播放器页请求 m3u8 → `WvCaptureClient` 拦下 → 带 UA/Referer
   header 喂给 mpv。实测 9 秒取直链。

### 环回代理修复（同批提交）
系统代理一设就会把壳子环回调用劫进 mihomo → 桥 ping 失败、端口螺旋。
`OkProxySelector`（127.0.0.1/localhost/::1 强制 NO_PROXY）、`TlsClientManager.ping`、
`GatewayWebViewClient.fetch` 三处显式 `Proxy.NO_PROXY`。
