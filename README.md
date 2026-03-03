# Redirects Plugin

一个为 Halo 2.x 准备的最小重定向插件，用来在后台维护旧路径到新路径的跳转规则。

当前开发和测试基线：

- Halo Docker 镜像：`halohub/halo:2.22.14`
- Halo 插件平台 BOM：`run.halo.tools.platform:plugin:2.20.20`
- Java：21
- 最低兼容版本（已实测）：Halo `2.19.3`

当前实现：

- 在 Halo 插件设置页维护多条重定向规则
- 支持批量粘贴导入（多行文本）
- 支持目录级重定向（保留子路径）
- 支持站内路径和外部 URL
- 支持 301 / 302
- 可选保留原请求的查询参数
- 支持通过 Console API 导入 / 导出 CSV、XLSX
- 规则变更后自动重新加载，无需重启 Halo

本地构建（不依赖宿主机 JDK）：

```bash
./scripts/build-in-docker.sh
```

启动本地 Halo 测试环境：

```bash
./scripts/run-halo-test.sh
```

这个脚本会自动：

- 构建最新插件 Jar
- 初始化本地 `.halo2` 数据目录（首次运行）
- 在 Halo 的本地 H2 数据库里注册 `redirects` 插件资源
- 在重新启动前停止已有的本地测试容器，避免 H2 文件锁

默认地址：

- Halo: http://localhost:8090

兼容性说明：

- 已本地实测通过：Halo `2.19.3`、`2.21.10`、`2.22.14`
- 插件 `requires` 已调整为 `>=2.19.3`
- 之所以不直接标成 `>=2.19.0`，是因为当前只实际验证到了 `2.19.3`

批量添加格式：

```text
/old-post -> /new-post
/old-post -> /new-post -> 301
/old-post,/new-post,301,optional note
/docs -> /knowledge -> 301 -> DIRECTORY
/guides,/docs,301,,DIRECTORY
```

- 每行一条
- 不写状态码时默认 `301`
- 支持 `->`、`=>` 和逗号分隔
- 第 4 列可写备注，第 5 列可写匹配方式（`EXACT` / `DIRECTORY`）
- 以 `#` 开头的行会忽略
- 如果和下方逐条规则有相同来源路径，逐条规则优先

导入 / 导出接口：

- 导入：`POST /apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects/rules/import`
- 导出：`GET /apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects/rules/export?format=csv`
- 导入时支持 `mode=replace|append`，`replace` 会清空现有批量文本规则并用文件内容覆盖表单规则

已知上游问题：

- 在 Halo Console 中，插件自带的前端脚本如果加载失败或注册异常，可能连带影响其他插件的左侧菜单项显示，例如“应用市场”。
- 这类问题理论上应该由 Halo Console 做插件级隔离，不应因为单个插件的控制台扩展异常而污染其他插件菜单。
- 因此当前 `0.1.3` 版本已暂时移除自定义 `console/` 前端资源，只保留插件设置页和后端能力，避免继续触发该类问题。

注意：

- 如果你修改了仓库地址或发布渠道，记得同步更新 `src/main/resources/plugin.yaml` 和 `scripts/register-test-plugin.sh` 里的仓库链接。
- 首次启动 Halo 时会初始化 `.halo2` 数据目录，可能需要等待几十秒。
- 如果你切换了 Halo 主版本，建议先清理旧容器再重新启动：`docker compose down && ./scripts/run-halo-test.sh`
