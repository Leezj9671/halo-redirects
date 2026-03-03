# Redirects Plugin

一个为 Halo 2.x 准备的最小重定向插件，用来在后台维护旧路径到新路径的跳转规则。

当前开发和测试基线：

- Halo Docker 镜像：`halohub/halo:2.22.14`
- Halo 插件平台 BOM：`run.halo.tools.platform:plugin:2.22.11`
- Java：21

当前实现：

- 在 Halo 插件设置页维护多条重定向规则
- 支持批量粘贴导入（多行文本）
- 支持站内路径和外部 URL
- 支持 301 / 302
- 可选保留原请求的查询参数
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

批量添加格式：

```text
/old-post -> /new-post
/old-post -> /new-post -> 301
/old-post,/new-post,301,optional note
```

- 每行一条
- 不写状态码时默认 `301`
- 支持 `->`、`=>` 和逗号分隔
- 以 `#` 开头的行会忽略
- 如果和下方逐条规则有相同来源路径，逐条规则优先

注意：

- 如果你修改了仓库地址或发布渠道，记得同步更新 `src/main/resources/plugin.yaml` 和 `scripts/register-test-plugin.sh` 里的仓库链接。
- 首次启动 Halo 时会初始化 `.halo2` 数据目录，可能需要等待几十秒。
- 如果你切换了 Halo 主版本，建议先清理旧容器再重新启动：`docker compose down && ./scripts/run-halo-test.sh`
