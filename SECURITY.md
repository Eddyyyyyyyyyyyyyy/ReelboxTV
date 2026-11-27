# 安全说明

## 报告问题

请通过 GitHub 仓库的 **Security → Report a vulnerability**（私有安全公告）提交，
不要在公开 issue 中贴出订阅地址、Cookie、账号或可利用细节。

## 需要了解的风险

### 第三方 spider jar

Reelbox 的在线源依赖订阅配置里的 `spider` jar。应用会下载该 jar，并通过反射调用其中的类，
**jar 代码在应用进程内、以应用权限运行**。这意味着：

- 恶意订阅可以让 jar 读取应用数据、发起网络请求或执行任意逻辑；
- 只应加载你信任的订阅来源，不要导入陌生人提供的配置；
- 不要在 issue / 截图 / 日志里公开完整订阅地址（可能含账号或私有令牌）。

### 明文 HTTP

应用允许明文 HTTP（`usesCleartextTraffic=true`），用于局域网 Alist / WebDAV / Emby。
在公网使用时请改用 HTTPS，或通过可信网络访问。

### 本地数据

播放历史、进度、设置、网盘 Cookie 与海报缓存均保存在本机应用私有目录，不会上传。
清理应用数据会同时清除这些内容。

## 支持范围

仅对仓库最新 Release 版本接受安全报告。
