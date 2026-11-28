# Plugin API v1：当前协议与接入关系

## 实现状态

此文描述当前源码，不代表真机已验证。Manifest 支持核心、目录、音乐库、收藏、下载及认证能力，
完整方法和 UI 接线见 [能力与认证接入](05-capabilities-and-authentication.md)。不能用空实现冒充支持。

```text
第三方 API / 授权测试服务
  → 插件默认导出对象（全部平台适配）
  → SDK 打包生成 globalThis.meloxPlugin
  → PluginIsolatedService（QuickJS + 独立 UID）
  ↔ PluginRuntime ↔ PluginHttpBroker / PluginStorage
  → PluginMusicProvider（标准字段校验与类型映射）
  → MusicProviderRegistry / 共享 DynamicMusicProviders
  → SearchScreen / ProviderPlaybackCommands / MeloXProviderLyricsLoader
  → ProviderPlaybackResolver → PluginMediaDataSource → Media3
```

宿主不读取第三方平台的 songmid、hash、privilege、VIP 字段；只接受 SDK 标准数据。
平台错误到标准错误/播放不可用状态的转换也由插件完成。

## 协议契约

- `plugin-sdk/schema/manifest.schema.json`：Manifest 结构。
- `plugin-sdk/schema/protocol-v1.schema.json`：搜索、歌曲、音质、播放、歌词结构。
- `plugin-sdk/src/`：TypeScript 接口与 `definePlugin`、`PluginError`。
- `PluginManifestParser` / `PluginResultValidator` / `PluginMusicProvider`：Android 校验与映射。
- `plugin-sdk/test/fixtures/manifest-cases.json`：SDK 与 Kotlin 共用的接受/拒绝用例。

当前没有从 Schema 自动生成全部 Kotlin/TypeScript 代码；完整模型的双端一致性验证仍待完成。
Manifest 用例一致性不能代表所有返回类型都已一致。

接口只传 JSON；不依赖 Android Bundle、MediaItem、CookieManager 或 Compose 类型，便于后续
Windows/Web 实现独立的 Host Context。当前仓库不提供这两个平台的运行时。

## Manifest

参考完整样例：`plugin-sdk/examples/test-tone/manifest.json`。

| 字段 | 约束 |
| --- | --- |
| `id` | 稳定的小写分段 ID，如 `org.example.music`；宿主添加 `plugin:` 命名空间 |
| `name` | 非空，最多 120 字符 |
| `version` | SemVer，最大 80 字符；当前不接受 `+build` 元数据 |
| `apiVersion` | 数字 `1`，不接受字符串或其他版本 |
| `entry` | ZIP 内相对 `.js` 路径，无绝对路径、反斜杠、`.`/`..` |
| `capabilities` | 去重数组，完整枚举和必需方法见 Manifest Schema 的 `x-capability-methods` |
| `networkDomains` | 精确的小写 ASCII 域名，不允许通配、IP literal、端口或协议 |

合法 ID 不等于已安装、可信或启用。来源身份不会因插件被卸载而重写为内置来源。

## 调用签名

```ts
search(query, page, pageSize, host): Promise<MusicPage<MusicTrack>>
resolvePlayback(track, quality, host): Promise<PlaybackResolution>
resolveFallback(track, quality, host): Promise<PlaybackResolution>
lyrics(track, host): Promise<LyricsResult>
```

`host` 总是最后一个参数。每次调用重新加载入口；插件不能依赖上次调用留下的全局变量。
额外未声明的方法不会自动成为宿主 UI 的能力。

## 数据规则

歌曲必须有 `id`、`title`、1–32 个带 `name` 的 `artists`。资源 ID 是不透明字符串，最大
4096 UTF-8 字节。宿主将歌曲、歌手及专辑 ID 绑定到声明插件的 `SourceId`，插件不能指定
另一个来源。SDK 不传 Android 的 `ProviderTrackMetadata`。

分页从 1 开始，每页 1–100 条；响应 `page/pageSize` 必须和请求一致，`items.length` 不能
超过请求页长，`hasMore` 必须是布尔值。插件浏览器支持上下页；跨源聚合保留每源分页元数据，
聚合搜索 UI 支持上下页，每源 total/hasMore 单独保留。

`PlaybackResolution` 的 `type` 支持 playable、preview、loginRequired、subscriptionRequired、
regionRestricted、copyrightRestricted、unavailable。playable 的 `headers` 映射到宿主
`requestHeaders`；`quality` 请求参数和 `actualQuality` 返回值意义不同。普通 Provider 的 URL
使用绝对 HTTPS，域名须在权限列表中；过期 URL 拒绝使用。不支持 DRM 或自定义 Media3 实现。

`resolveFallback` 只由声明 `resolver` 能力的插件实现，输入是**另一个来源**的 track
（`track.source` + `track.id`）。为避免第三方补源常见的 HTTP CDN 直链被错误拒绝，该方法的
`playable.url` 允许 HTTP(S)、不要求在 `networkDomains` 内；但仍必须是渐进式音频、不能使用
`credential: "active"`，且宿主不会向媒体 URL 注入插件账号凭据。普通 `resolvePlayback`
仍保持 HTTPS + 域名白名单。

歌词的 `timeMs` 是从歌曲开头计算的毫秒值，必须非递减；最多 2000 行。`text`、翻译和
罗马音由插件转换。当前无逐字 transport，不能把行级歌词声称为精确逐字数据。

当前图片请求尚未经过插件网络代理，Provider 会抑制插件封面，避免未经权限校验的 Coil 请求。
标准 `total` 已保留到 MusicPage，目录详情的轨道页也保留总数。

## 错误

SDK 的 `PluginError` 提供稳定 code。JS 拒绝会返回错误码；未知异常转换为 PLUGIN_ERROR，
原始异常堆栈和可能包含 Token 的信息不会写入全局日志。宿主当前将这些码包装成调用失败，
尚未提供全部错误码对应的本地化 UI。

## 额外能力

目录/音乐库/收藏/下载/认证的方法、Manifest 及标准返回类型见
[能力与认证接入](05-capabilities-and-authentication.md)。宿主检查必需方法后才发布 Provider；
UI 按声明隐藏未提供的栏目和操作。全部平台字段转换依然由插件完成。

## 声明式界面扩展

使用现有 `.melox-plugin` 包。Manifest 以 `apiVersion: 1` 声明可选 `ui` 能力，并提供 `contributions`。

```json
{
  "capabilities": ["ui"],
  "contributions": [
    { "id": "connection", "title": "服务器连接", "slot": "extensions" }
  ]
}
```

每个插件最多声明 32 个入口，入口 ID 在插件内唯一。新增 `slot` 会触发升级权限确认。启用后由宿主显示入口，停用或卸载会移除入口和打开的页面。旧插件可以继续使用已有能力。声明 `ui` 的插件需要支持本节的新版宿主。

| `slot` | 宿主位置 |
| --- | --- |
| `extensions`, `pluginDetails`, `services` | 设置扩展及其搜索、插件详情、音乐服务 |
| `search` | 搜索页的插件扩展，进入时带入当前查询词 |
| `home`, `explore`, `library` | 该插件作为音源时的首页、发现、音乐库 |
| `songMenu`, `playerMenu` | 该插件曲目的歌曲菜单与播放器更多菜单 |
| `playlist`, `album`, `artist` | 插件目录中的歌单、专辑、歌手详情 |
| `lyrics`, `queue` | 该插件当前曲目的歌词与队列面板 |
| `navigation`, `playerPage` | 主导航内的扩展入口、播放器页面选择器内的扩展入口 |

`uiPage(request, context)` 返回 `UiPage`。请求包含入口 `entry`、当前页面 `page`、从 1 开始的页号 `index`、查询词 `query`。资源入口另附带 `scope`，包含本插件拥有的 `type` 和资源 `id`。首次打开时 `page` 等于入口 ID。`link` 将同插件子页加入返回栈。

`UiPage` 提供 `settings`、`connection`、`collection`、`detail`、`search`、`status` 六种模板。模板共用原生控件渲染器，`search` 另外显示搜索框。每页最多 50 个控件，且控件 ID 在页内唯一。可选 `tracks` 最多 30 条，使用标准歌曲模型。插件声明 `playback` 后可从页面启动其可播放曲目。`hasMore` 控制下一页入口。

控件类型为 `toggle`、`singleChoice`、`multiChoice`、`slider`、`number`、`text`、`secret`、`url`、`button`、`link`、`status` 和 `qr`。选择项使用稳定 ID，URL 控件提交 HTTP(S) 地址，插件通过自己的存储保存业务设置，按现有网络和凭据权限访问服务。`secret` 输入仅在用户提交时传给声明该控件的插件，值不写入页面状态恢复或宿主诊断。

`uiAction(request, context)` 接收同样的页面上下文，加上控件 ID `control` 和用户提交的 `value`。无参数按钮省略 `value`。返回可选的 `message` 和 `refresh`，默认重新加载当前页面。界面 API 只在用户操作对应入口时运行，后台调度、专用登录回调和实时房间同步由独立宿主能力处理。

### 局域网直连

Manifest 的 `lanCookie` 声明服务端会话 Cookie 名称。插件须声明 `ui`，且使用独立的网络权限。用户在插件详情授权一个私网 IPv4 地址、协议和端口。API、封面与媒体沿用这组授权，直连禁止跳转。

`context.lan.origin()` 返回已授权地址，`signedIn()` 返回本地会话状态。用户提交 `uiAction` 时可以调用 `sha256(value)`、`signIn(token)` 和 `signOut()`。插件负责登录协议，宿主将会话加密保存，`credential: "active"` 仅向已授权服务器发送 Cookie。封面请求也使用关联会话。更换地址和撤销授权会使旧会话失效。
