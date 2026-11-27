# 参与贡献

提交代码前请先读 [AGENTS.md](AGENTS.md)。那里记录了本项目的开发纪律，几条最关键的：

- **一次只做一件事**：一个 PR 一个主题，改解析器就别顺手改 UI
- **先写会失败的测试再修 bug**：跳过"看它失败"这一步，等于不知道测试有没有测到东西
- **按键逻辑必须走 `ui/component/KeyRouting.kt`**：不要在 Composable 里手写 `when (key)`，也不要新增第二套焦点生命周期
- **注释只写"为什么"**：删掉之后后来者会不会踩同一个坑，不会就别写

## 提 Issue

- 用 Bug 模板填写设备型号、Android 版本、App 版本、复现步骤
- **不要**附带订阅地址、Cookie、账号、二维码或任何私有令牌
- 安全问题请走 [Security → Report a vulnerability](https://github.com/Eddyyyyyyyyyyyyyy/Reelbox/security/advisories/new)，不要开公开 Issue

## 提 PR

```bash
# 提交前本地过一遍
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

要求：

- 新增按键逻辑必须写进 `KeyRouting.kt` 并补 `KeyRoutingTest` 用例
- 纯逻辑改动用单测验证，UI 布局和焦点走位需要真机说明（贴截图或描述过程）
- **不要为了让编译通过而加宽泛的 ProGuard 规则**（`-keep class ** { *; }`、`-dontshrink`、`-ignorewarnings`）。如果 R8 裁剪掉了必需的东西，请定位到具体类再加最小范围的 keep，并写明原因
- 新增第三方依赖前请说明：有没有 Consumer ProGuard Rules、是否用反射/JNI、是否影响体积

## 代码风格

- Kotlin，4 空格缩进，不强制分号
- 用户可见文字一律进 `res/values/strings.xml`
- 踩过坑的地方用 `// ★ 不要 XXX —— 为什么（现象）` 留"墓碑"

## 许可证

本项目按 GPL v3 发布。提交 PR 即表示你同意将代码以同一许可证发布。
