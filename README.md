# 开屏跳过（AdSkipper）

一个 Android 开屏广告自动跳过应用。通过无障碍服务识别明确的“跳过”控件，优先执行节点点击，失败后使用坐标手势，并在点击后验证原目标是否消失。识别与点击均在本地完成，仅在用户手动更新规则订阅时联网。

## 当前功能

- Kotlin、Jetpack Compose，最低 Android 8.0（API 26）
- 应用进入前台或检测到开屏 Activity 后，扫描 12 秒
- 匹配简繁中文/英文跳过文字、内容描述和常见广告 SDK 控件 ID
- 支持把同一按钮内的倒计时、分隔符和“跳过”子控件组合识别
- Android 11+ 在节点规则未命中时进行本地 OCR 兜底；截图仅保存在内存，处理后立即释放
- OCR 采用上方区域或明确广告上下文约束，并排除会员、视频、教程、下载、支付等高风险文字
- 支持包名、Activity、文字正则、ID/ID 正则、排除匹配、屏幕区域和节点边界规则
- 支持自定义绝对/归一化点击位置
- 支持 SKIP v3 订阅导入与运行中热更新
- 支持从 GitHub Releases 检查应用更新、下载 APK、校验 SHA-256 与签名并调起系统安装器
- 节点 `ACTION_CLICK` 与坐标 `dispatchGesture` 两级点击
- 点击后验证目标消失，再记录“已确认跳过”
- 总开关、本地日志，默认对所有非保护应用启用
- 系统设置和安装器等高风险页面保护
- 内置测试广告页面
- 无后台网络请求、无截图上传、无云端 AI、无 Root

## 构建

需要 JDK 17、Android SDK 35。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

正式发布使用独立签名。将 `keystore.properties.example` 复制为 `keystore.properties`，填入本地密钥配置后运行：

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

也可以通过 Android Studio 打开项目并运行到真机。签名文件和 `keystore.properties` 已被 Git 忽略，不能提交。

## 使用

1. 安装并打开 App。
2. 点击“开启无障碍服务”，在系统设置中找到“开屏跳过”并授权。
3. 进入“规则与订阅”，按需下载 SKIP v3 专用规则；规则会即时生效。
4. 打开其他应用，随后在“概览”页查看累计跳过、点击成功率和最近记录。
5. 如果国产 ROM 后台限制导致服务停止，在系统电池设置中允许后台运行。

## 应用内更新

1. 打开“状态 → 应用更新”，应用读取 GitHub Releases 中的 `update.json`。
2. 发现更高 `versionCode` 后，可查看更新说明并下载 APK。
3. 下载完成后会核对文件大小、SHA-256、包名和发布签名；不一致的文件会被删除。
4. 首次安装更新时，按系统提示允许本应用安装未知应用，再返回点击“安装更新”。
5. Android 会显示系统安装确认页；普通应用不能静默安装更新。

更新元数据和 APK 均由 GitHub Actions 在推送 `v*` 标签后生成。应用不内置 GitHub Token。

## 发布新版本

1. 增加 `versionCode`，更新 `versionName` 和 `RELEASE_NOTES.md`。
2. 提交并推送代码。
3. 创建同名标签，例如 `v1.1.0`，再推送标签。
4. `.github/workflows/release.yml` 自动测试、Lint、构建签名 APK，并上传 `AdSkipper.apk`、`update.json` 和 `checksums.txt`。

GitHub 仓库需要配置以下 Secrets：`ADSKIPPER_KEYSTORE_BASE64`、`ADSKIPPER_KEYSTORE_PASSWORD`、`ADSKIPPER_KEY_ALIAS`、`ADSKIPPER_KEY_PASSWORD`。发布密钥丢失会导致老用户无法直接升级，必须长期备份。

## 诊断日志

每条日志包含包名、Activity、App 版本、Android 版本、分辨率、扫描耗时和原因代码。点击失败时还会记录目标控件、节点点击是否被接受、父节点查找过程、手势结果及点击后的验证结果。

未找到目标时，日志会保存最多 12 个相关候选控件的摘要，帮助补充文字或 ID 规则。`INFO / NO_RULE_MATCH` 表示扫描窗口内未匹配到按钮，不一定代表本次存在广告。日志完全保存在本地，不上传屏幕或节点内容。

详细技术字段仅保存在本地诊断文件中；App 的“概览”页面只显示人性化统计和最近记录，不展示原因代码或控件树。

## 专用规则格式

`app/src/main/assets/rules.json`：

```json
{
  "version": 1,
  "rules": [
    {
      "packageName": "example.app",
      "activityName": "example.app.SplashActivity",
      "viewIds": ["example.app:id/circular_skip"],
      "viewIdPatterns": [".*/circle_skip_[0-9]+"],
      "textPatterns": ["^跳过\\s*\\d*\\s*秒?$"],
      "excludeTextPatterns": [".*会员.*"],
      "region": "TOP_RIGHT",
      "clickAction": { "x": 0.9, "y": 0.08, "normalized": true }
    }
  ]
}
```

- `activityName` 可省略，但限定后更安全。
- `viewIds` 支持完整资源 ID 或 `/` 后的资源名称。
- 专用规则优先于通用规则。
- `region` 支持 `ANY`、`TOP_RIGHT`、`TOP_LEFT`、`TOP`、`BOTTOM`。
- 可配置 `bounds`、`maxTextLength`、`excludeViewIds` 和归一化/绝对 `clickAction`。
- “规则与订阅”支持 SKIP v3 的 `skipIds`、`skipTexts`、`skipBounds`、`activityName` 和 `click`，导入后无需重启服务。
- SKIP 及其默认规则采用 GPL-3.0；应用不捆绑第三方订阅副本，只有用户手动下载后才保存到本地。

## 识别与点击流程

```text
窗口变化
  → 启动时间窗口和包名安全检查
  → 控件树遍历（单次最多 2000 节点）
  → 专用 ID / 专用文字 / 通用 ID / 通用文字
  → 可见性和尺寸检查
  → ACTION_CLICK
  → 550ms 后验证目标是否消失
  → 坐标手势兜底并再次验证
  → 节点未命中时本地 OCR（Android 11+，最多 4 次）
  → OCR 坐标点击后再次识别并验证目标消失
  → 写入本地结果日志
```

## 已知限制

- Android 8–10 仅使用控件规则；Android 11+ 才支持本地 OCR。
- 纯图形“×”、系统禁止截图或文字严重变形的广告仍可能无法识别。
- 不捆绑 SKIP/GKD 的规则副本；SKIP 订阅需手动导入，GKD 高级选择器格式尚不兼容。
- 导入的规则仍受 App 版本和设备分辨率影响，不代表全部经过本机验证。
- 12 秒是保守的通用窗口，不等于每次启动都会有点击。
- “目标消失”比“手势执行完成”更可靠，但仍不能替代大规模真机对照测试。
- 国产 ROM 的无障碍保活行为因系统版本而异。
- 不应该尝试跳过没有提供关闭/跳过入口的广告。

## 建议验收方式

固定设备、App 版本和账号，对目标 App 分别冷启动至少 10 次，记录：

```text
真实成功率 = 广告实际关闭次数 / 出现可跳过广告的总次数
误触率 = 非广告按钮误点次数 / 总扫描启动次数
```

与 SKIP 对比时应使用同一手机、同一 App 版本和相近测试条件。
