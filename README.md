# Redirects Plugin

一个为 Halo 2.x 准备的重定向插件，用来在后台维护旧路径到新路径的跳转规则。

当前开发和测试基线：

- Halo 插件平台 BOM：`run.halo.tools.platform:plugin:2.20.20`
- 前端依赖锁定在 Halo `2.19` 对应版本（`@halo-dev/components` / `api-client` / `console-shared` `2.19.0`）
- Java：21
- 最低兼容版本（已实测）：Halo `2.19.3`

## 功能

- 在插件详情页的「规则管理」标签页维护规则：表格列表、搜索与筛选、新建 / 编辑 / 删除、单条启停、批量删除
- 测试地址：输入 URL 或路径，显示会命中哪条规则、跳转到哪里（会继续跟踪站内跳转链）；没命中时可一键为它新建规则
- 批量添加（多行文本）、导入 / 导出 CSV（UTF-8，Excel 可直接打开和另存为 CSV）
- 精确匹配和目录匹配（目录匹配保留子路径：`/docs/a -> /knowledge/a`）
- 站内路径和外部 URL
- 301 / 302 / 307 / 308，以及 410（内容已删除，不跳转）
- 中文等非 ASCII 路径（来源路径可直接写中文或百分号编码，跳转地址自动编码）
- 只重定向 GET / HEAD 请求，表单提交等不受影响
- 自动跳过会形成循环（或超过 20 跳）的规则，在规则列表中标红并在日志中给出 WARN
- 可选保留原请求的查询参数
- 规则变更后立即生效，无需重启 Halo（包括通过 Halo 通用接口修改 `RedirectRule`）

「基础设置」标签页只保留两个开关：启用重定向、保留查询参数。

## 数据存储与升级

- 自 `0.3.0` 起，每条规则是一个独立的 Halo 自定义模型 `RedirectRule`（`redirects.halo.run/v1alpha1`），有稳定的名称（ID），可单独增删改。
- 之前版本保存在插件设置里的规则（逐条规则和批量文本）会在升级后启动时自动搬进 `RedirectRule`，随后从设置中清除。之后若有旧脚本再往设置里写入旧格式规则，也会在下一次加载时被搬过去；来源路径和匹配方式相同的规则会更新而不是重复创建。
- 搬迁失败时（例如数据异常），旧规则仍然按原样生效，并在下一次加载时重试。
- 同一来源路径 + 匹配方式只能有一条规则；新建或编辑时重复会被拒绝，批量添加 / 导入时会更新已有规则。

## 批量添加格式

```text
/old-post -> /new-post
/old-post -> /new-post -> 301
/old-post,/new-post,301,optional note
/docs -> /knowledge -> 301 -> DIRECTORY
/guides,/docs,301,,DIRECTORY
/deleted-post -> 410
/old-page,,410,已下线
```

- 每行一条，支持 `->`、`=>` 和逗号分隔，以 `#` 开头的行会忽略
- 不写状态码时默认 `301`；支持 `301` / `302` / `307` / `308` / `410`，无法识别的状态码按 `301` 处理
- 状态码为 `410` 时目标地址留空
- 第 4 列可写备注，第 5 列可写匹配方式（`EXACT` / `DIRECTORY`）

## Console API

前缀：`/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects`

| 方法与路径 | 说明 |
|---|---|
| `GET /rules` | 规则列表（含是否因循环被跳过） |
| `POST /rules` / `PUT /rules/{name}` / `DELETE /rules/{name}` | 新建 / 修改 / 删除单条规则 |
| `POST /rules/-/delete` | 批量删除，body：`{"names": [...]}` |
| `POST /rules/-/bulk` | 批量添加，body：`{"text": "..."}` |
| `GET /rules/-/test?url=...` | 测试地址 |
| `POST /rules/import?mode=append\|replace` | 导入 CSV（`replace` 会先删除全部规则） |
| `GET /rules/export?format=csv` | 导出 CSV |
| `GET /settings` / `PUT /settings` | 读取 / 修改开关；`PUT` 带 `rules` 时会替换全部规则 |

目前接口没有单独的角色模板，需要超级管理员账号。

## 开发

前端在 `ui/`，构建产物输出到 `src/main/resources/console/` 并随仓库提交（CI 不安装 Node）。修改前端后需要重新构建：

```bash
cd ui && npm ci && npm run build
```

构建插件 Jar（不依赖宿主机 JDK）：

```bash
./scripts/build-in-docker.sh
```

启动本地 Halo 测试环境（默认地址 http://localhost:8090）：

```bash
./scripts/run-halo-test.sh
```

端到端测试（一次性容器中安装插件，校验跳转、规则管理接口、测试地址、旧数据迁移和重启后加载）：

```bash
./scripts/e2e-test.sh halohub/halo:2.26.1 build/libs/redirects-0.3.0.jar
```

注意：

- Halo 按插件版本缓存合并后的前端资源。用**同一个版本号**的 Jar 覆盖安装后，需要重启 Halo 才能看到新的界面。
- `src/main/resources/META-INF/plugin-components.idx` 列出 Halo 要创建的组件；新增 `@Component` 类时必须加进去（`PluginComponentsIndexTest` 会检查）。
- 如果你修改了仓库地址或发布渠道，记得同步更新 `src/main/resources/plugin.yaml` 和 `scripts/register-test-plugin.sh` 里的仓库链接。

## 兼容性

- `0.3.0` 已用 e2e 脚本实测：Halo `2.19.3`、`2.22.14`、`2.26.1`、`2.27.0-beta.1`；「规则管理」标签页已在 `2.19.3` 和 `2.26.1` 中实际操作验证（测试地址、新建、编辑、启停、批量添加、搜索、批量删除）。
- 规则管理放在插件详情页的标签页（`plugin:self:tabs:create`，Halo 2.19 已支持），不在左侧菜单注册路由；已确认安装后「应用市场」等其他菜单正常显示。
- 前端只依赖 Halo 页面提供的 `Vue`、`HaloComponents`、`HaloApiClient` 全局对象，在 2.19 和 2.26 中都存在。
- `0.2.0` 移除了 XLSX 导入导出：插件 Jar 从未打包 Apache POI，在 Halo 中调用会抛 `NoClassDefFoundError`，请求一直挂起。
- 自 `0.1.7` 起，插件不依赖 `SettingFetcher`（Halo 2.23 起由类改为接口）和 `PluginConfigUpdatedEvent` 的配置载荷（Halo 2.25 起改为 Jackson 3 节点），直接读写插件自己的 ConfigMap。
- 插件 `requires` 为 `>=2.19.3`（只实际验证到了 `2.19.3`）。
