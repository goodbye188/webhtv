# mihomo 代理内核模块 — 进度

## 状态: 代码已写完并推远端 (commit f429b36, main 分支)
- 下一步: 明早去 GitHub Actions 触发「打包AI影」验证编译; 绿勾后下载 APK 装机测试

## 已完成
- `MihomoManager.java`（app/src/main/java/com/fongmi/android/tv/proxy/）
  - 二进制下载（GitHub release, 走 GithubProxy 加速, filesDir/proxy/mihomo, gzip→ELF, setExecutable）
  - 订阅拉取 → normalizeConfig（改写 mixed-port/external-controller/secret, 去重防 YAML 重复键）→ config.yaml
  - start/stop/restart（端口占用检测, ext-ctl=port+1, secret=webhtv, 5s 等待监听）
  - ext-ctl: 测速(/proxies)、当前节点、手动选节点(PUT /proxies/{name})
- `Setting.java`：4 个 key（mihomo_enabled / mihomo_port 默认18890 / mihomo_subscription）
- `dialog_mihomo_source.xml` + `MihomoSourceDialog.java`（设置卡片, 照 TmdbSourceDialog 模式）
- `fragment_setting.xml`：设置页 TMDB 下方加「代理内核」条目 + 状态文本
- `SettingFragment.java`（mobile）：点击开卡片, initView 刷新状态
- `App.java`：后台服务启动处加内核自启（启用+已下载+有config 时拉起）
- strings 三语（en/zh-rCN/zh-rTW）

## 设计要点（与星落对齐）
- 端口默认 18890 可改（不是 7890）
- 订阅手动刷新, 不做内置自动订阅
- 按源选择性代理: 爬虫源 JSON 顶层写 `proxy` 字段(hosts 匹配 + urls 指向 http://127.0.0.1:18890) → 走内核; 不写的直连。OkProxySelector 管道现成, 爬虫侧零改动
- 内核不进 APK（首用下载 18MB gz → 51MB ELF, 离线可用）

## 待验证（装机后）
- [ ] 设置页点「代理内核」→ 填订阅 → 更新（拉 config + 起内核）
- [ ] 状态文本变化（未下载→运行中·节点xxx）
- [ ] 端口占用时提示换端口
- [ ] 写一个测试爬虫源 JSON 配 proxy 指向 127.0.0.1:18890, 验证命中 host 走节点、未命中直连
- [ ] 杀 app 重进, 内核自启

## 已知风险
- Android 沙箱直接 exec 二进制: lab 模块已验证可行(同目录机制), 但 mihomo 在 filesDir/proxy 下需 755 权限, 已 setExecutable
- normalizeConfig 只认行首 `mixed-port:`/`external-controller:`/`secret:`（行内 # 注释会整行替换, 可接受）
- ctl() 用 HttpURLConnection（不走 OkHttp 选择器, 避免循环依赖）
