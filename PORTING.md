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
