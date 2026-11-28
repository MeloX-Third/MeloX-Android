# 界面扩展示例

示例包在 16 个位置注册入口，使用 12 类控件和 6 种页面模板。曲库沿用 SDK 的合成测试音。设置动作只写示例选项，密码输入仅供虚构数据演示。

在 `plugin-sdk` 目录打包后，将生成的文件导入 MeloX 的「音乐源插件」并启用。

```sh
node tools/cli.mjs pack examples/ui/manifest.json examples/ui/index.ts melox-ui-demo-1.0.0.melox-plugin
```

在「设置 → 扩展」可以打开表单并验证开关持久化。在插件详情将示例设为主音源，即可查看首页、发现和音乐库入口。打开示例歌单、专辑或歌手详情，或播放测试音，可查看对应资源和播放器的扩展入口。
