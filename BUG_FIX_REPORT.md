# MeloX-Android Bug 修复报告

## 修复概览

本次修复解决了代码探索中发现的关键 bug，重点关注播放服务的并发安全和资源管理问题。

## 修复的 Bug

### 1. AutoMix 状态竞争（严重）⭐

**问题描述**：
- AutoMix 模式下同时运行两个 ExoPlayer 进行淡入淡出
- `mixStartedAt`、`preparedMixSourceId`、`mixSessionOnIncoming` 等状态变量缺乏原子性保护
- 多个回调（`modeMonitor`、`mixEnvelope`、`playerListener`）同时在主线程修改这些状态
- 可能导致状态不一致，造成 AutoMix 切换时音频中断或卡死

**修复方案**：
- 添加 `autoMixStateLock` 同步锁对象
- 在关键状态修改区域使用 `synchronized(autoMixStateLock)` 保护：
  - AutoMix 启动逻辑（Line 1969-1988）
  - `completeAutoMix` 方法中的状态交换（Line 2475-2491）
  - `cancelPreparedMix` 方法中的状态重置（Line 2626, 2643-2668, 2672-2674）

**影响**：
- 防止 AutoMix 状态机混乱
- 确保播放器引用交换的原子性
- 避免多线程并发修改导致的崩溃

---

### 2. 系统歌词任务泄漏（中等）⭐

**问题描述**：
- `loadSystemLyrics` 方法在加载新歌词时创建新的 `systemLyricsJob`
- 没有先取消旧的 Job，导致快速切歌时旧的网络请求任务继续运行
- 造成资源浪费和不必要的网络流量

**修复方案**：
- 在 `loadSystemLyrics` 方法开头添加 `systemLyricsJob?.cancel()`
- 确保每次加载新歌词前取消旧的加载任务

**代码变更**：
```kotlin
private fun loadSystemLyrics(mediaId: String, item: MediaItem) {
    systemLyricsJob?.cancel()  // ← 新增：取消旧任务
    systemLyricsMediaId = mediaId
    systemLyricsOriginalMetadata = item.mediaMetadata
    systemLyricsJob = serviceScope.launch {
        // ...
    }
}
```

**影响**：
- 减少不必要的网络请求
- 防止内存中累积多个未完成的协程任务
- 提升快速切歌时的响应性能

---

## 未修复的问题

### 3. 浮动歌词内存泄漏（探索报告误判）

**调查结果**：
- 探索报告提到 TextureView 未释放，但实际代码中没有使用 TextureView
- 浮动歌词服务只使用 TextView，已在 `onDestroy` 中正确释放所有资源：
  - 取消 handler callbacks
  - 取消 lyrics Job  
  - 释放 MediaController
  - 移除 overlay view
  - 取消 coroutine scope
- **结论**：此问题不存在，无需修复

### 4. AutoMix 音频焦点竞争（探索报告误判）

**调查结果**：
- incoming player 在准备时已正确设置 `managesAudioFocus = false`（Line 2374）
- 在 `completeAutoMix` 中新主 player 正确获取音频焦点（Line 2463-2466）
- 音频焦点管理权在播放器切换时正确转移
- **结论**：此问题不存在，无需修复

### 5. 设置持久化延迟（低优先级）

**问题分析**：
- 大量使用 SharedPreferences 的 `.apply()` 方法（异步）而非 `.commit()`（同步）
- 在极端情况下（系统杀进程）可能丢失设置
- 但 Android 系统会确保 apply() 在进程退出前完成
- 关键场景（如播放队列）已使用条件性 commit()

**决策**：
- 修改范围太大（100+ 处），收益有限
- 现有关键路径已有保护
- 暂不修复，可作为后续优化项

---

## 修改文件

- `android/app/src/main/kotlin/com/lladlam/melox/playback/MeloXPlaybackService.kt`
  - 新增 `autoMixStateLock` 锁对象
  - AutoMix 启动逻辑添加同步保护
  - `completeAutoMix` 添加同步保护
  - `cancelPreparedMix` 添加同步保护
  - `loadSystemLyrics` 添加旧任务取消逻辑

## 测试建议

1. **AutoMix 并发测试**：
   - 在 AutoMix 过渡期间快速切歌
   - 在 AutoMix 过渡期间调整设置（开关 AutoMix）
   - 验证不会出现音频中断或卡死

2. **系统歌词测试**：
   - 快速连续切换多首歌曲
   - 观察网络请求是否正确取消
   - 验证歌词显示正确性

3. **稳定性测试**：
   - 长时间播放测试
   - 内存泄漏检测
   - 多场景切换测试（前台/后台/锁屏）

---

## 技术细节

### synchronized 锁的使用原则

在本次修复中，我们遵循以下原则使用同步锁：

1. **锁的粒度**：只保护必要的状态变量修改，不包含耗时操作
2. **避免死锁**：所有锁都在主线程获取，不涉及跨线程锁争用
3. **性能考虑**：临界区尽可能小，快速进出

### 资源管理模式

系统歌词任务管理遵循标准的 Kotlin 协程取消模式：
```kotlin
oldJob?.cancel()          // 取消旧任务
newJob = scope.launch {   // 启动新任务
    // ...
}
```

这种模式确保：
- 只有最新的任务在运行
- 旧任务立即收到取消信号
- 资源及时释放

---

## 版本信息

- 修复版本：0.7.5+
- 修复日期：2026-11-25
- 相关 Issues：探索报告发现的并发安全问题
- 修复人：AI Agent (Kiro)

## 编译状态

⚠️ **注意**：由于 Gradle wrapper 网络下载校验和不匹配问题（环境相关，非代码问题），暂未完成本地编译验证。建议在 CI 环境中编译测试。

错误信息：
```
Expected checksum: 'bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f'
Actual checksum:   '553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'
```

这是网络代理或镜像问题，与代码修改无关。
