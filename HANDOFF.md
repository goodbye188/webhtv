# AI影 项目交接文档（新会话先读这个）

> 仓库：`C:/Users/78139/tvbox-src/meiyingshi`（本地） = `goodbye188/webhtv`（远端 main）
> 基座：Silent1566/webhtv（默影视，鱼壳 fish2018/webhtv 的 fork），applicationId `com.aimedia.tv`
> 用户：78139，零编程基础，全部由 AI 代做。手机是 arm64。

## 当前状态（2026-09-14 晚）

- 最新提交 `a0a9b3d`，CI 绿勾（run 34798194937），版本 5.6.1 / code 561
- 最新 APK 在桌面：`C:/Users/78139/Desktop/AI影-arm64-收藏联动.apk`（旧版 v2/v561 可删）
- **所有代码已推远端，无未提交工作，可随时开新会话**

## 已实现功能清单

1. **品牌**：应用名「AI影」、桌面图标（蓝渐变+白字 AI/影，`scripts/gen_ai_icon.py` 生成）
   - 坑：Android 8+ 会优先用 `mipmap-anydpi-v26` 自适应图标，已删该目录下 `ic_launcher*.xml` 强制走 PNG
2. **收藏分组**（`app/src/mobile/.../dialog/KeepGroupsDialog.java` + `KeepActivity`）
   - 分组存 SharedPreferences（key `keep_groups`，JSON 数组）；收藏的 group 字段存 DB
   - 收藏页顶部 Tab 标签条（All/全部 + 各分组），长按收藏项可移组
   - 新建分组后发 `RefreshEvent.keep()` → Tab 实时刷新，不用退出重进
3. **收藏时联动选分组**（`VideoActivity.showCollectGroupDialog()`，a0a9b3d）
   - 手动点 ★ 新增收藏时弹框：未分组 / 已有分组 / 「新建分组」（顶部输入框填名字）
   - 取消则不收；删收藏（★ 再点）仍直接删
4. **mihomo 代理内核**（`app/src/main/java/com/fongmi/android/tv/proxy/MihomoManager.java` + `MihomoSourceDialog`，详见 `PROGRESS-mihomo.md`）
   - 设置页「代理内核」卡片：填订阅 `https://ycdpq.pages.dev/sub?token=28`、端口 18890、手动「更新/测速/停止」
   - 首次启用才从 GitHub release 下载内核（gh-proxy 加速链），离线可用
   - **按源选择性代理，非全局**：爬虫源 JSON 顶层加 `"proxy": [{"name":"mihomo","hosts":["*.xxx.com"],"urls":["http://127.0.0.1:18890"]}]`，命中 hosts 的走节点，其他直连（OkProxySelector 管道，爬虫代码零改动）
   - 杀 app 重进内核自启（App.java 挂钩）

## 装机待验证（用户还没全部确认）

- [ ] 桌面图标是 AI影
- [ ] 收藏弹分组选择框 → 新建分组 → 收藏页 Tab 实时出现
- [ ] mihomo：填订阅 → 更新 → 状态"运行中" → 测速显示节点数
- [ ] 配一个带 proxy 字段的测试源，验证命中 host 走代理

## 环境与操作套路（详见长期记忆）

- GitHub「半通」（GFW），push 用 15s×N 轮裸推重试；GCM 存 token，裸推不再弹浏览器
- GCM 不认 socks5h 代理：取 token 时临时 `git config --global --unset http(s).proxy`
- CI 手动触发（Actions→打包AI影），~15min；API 触发偶发 422（过会自己好）
- 拉 CI 日志：`git credential fill` 取 token → `curl -sL -H "Authorization: token $T"` 拉 jobs logs（**必须 -L 跟随 302**）
- artifacts 下载：runs/{id}/artifacts → artifacts/{id}/zip
- 改代码→提交→push→触发 CI→绿勾→下包→`桌面\AI影-arm64-xxx.apk`
