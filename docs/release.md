# 发布 APK

向 GitHub 推送 `V` 开头的版本号 Tag 后，`.github/workflows/release.yml` 会自动构建并发布两个经过 R8 优化、稳定签名的 APK。

例如推送 `V2.1.36` 后将创建：

- Tag：`V2.1.36`
- Release 标题：`Release 2.1.36`
- Full 安装包：`Cithub-2.1.36-auto-captcha-performance.apk`（包含本机验证码识别和自动回填）
- Lite 安装包：`Cithub-2.1.36-manual-captcha-performance.apk`（手动输入验证码，不包含 ML Kit）

## 首次配置

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加以下 Repository secrets：

- `ANDROID_SIGNING_KEYSTORE_BASE64`：正式签名 keystore 文件的 Base64 内容。
- `ANDROID_SIGNING_STORE_PASSWORD`：keystore 密码。
- `ANDROID_SIGNING_KEY_ALIAS`：签名密钥别名。
- `ANDROID_SIGNING_KEY_PASSWORD`：签名密钥密码。

keystore 和密码不得提交到仓库。为了让已安装的正式版、滚动 Pre-release 和后续正式版都能直接覆盖更新，必须继续使用历史正式包对应的同一把密钥；工作流会强制校验证书 SHA-256 为 `f9de6015c070e755465c9eee74cb492853421bbe9fec3d64ccaf4dbc65ad02c1`。签名、不可调试标记、版本记录或 Baseline Profile 任一校验失败都不会发布 APK。

2026-07-18 之前的滚动 Pre-release 2.1.34 使用过 CI 临时证书 `5dd8c9cf5941888dab1f539fc08e58143803822b442b605c489444faff933189`，它无法直接覆盖为正式证书包；这批安装仅在迁移到统一证书时需要卸载一次。V2.1.24、V2.1.29 正式版已经使用上述稳定证书，可以直接覆盖升级；完成这次迁移后的所有 Pre-release 与正式版也可互相覆盖。

## 发布新版本

确认目标提交已推送后执行：

```bash
git tag V2.1.36
git push origin V2.1.36
```

Tag 必须使用 `V主版本.次版本.修订号` 格式，并与 `app/build.gradle.kts`、`VERSION_HISTORY.md` 的当前版本完全一致。应用默认只检查正式 Release；draft 不会触发更新提示，只有用户开启“预览版试用”后才会检查 Pre-release。
