# Ultrasonic

> ## 车机定制版（Nav音乐）
>
> 这一份不是上游原版，是为**比亚迪车机**（Android 7，1920×1080 横屏）改造的分支，接自建的
> Subsonic / OpenSubsonic 服务端（Navidrome）。基线是 **Ultrasonic 4.8.0**（minSdk 21），
> 而不是最新的 4.9.0 —— 后者已把 minSdk 提到 26（Android 8），在 Android 7 车机上装不上。
>
> - **构建**：`./gradlew :ultrasonic:assembleRelease`（JDK 17 + SDK 34；签名配置见下面「签名」）
> - **发版**：推送 `v*` 标签 → Actions 构建签名 APK → 生成 `update.json` → 创建 Release
>   （工作流文件在 `.github/workflows.disabled/release.yml`：GitHub 不允许用只有 `repo` 权限的
>   令牌写 `.github/workflows/`，把它在网页上移进 `.github/workflows/` 即启用）
> - **自动更新**：App 读 `update.json` 发现新版本，只用通知提醒；下载与安装都要司机确认，
>   行车中不弹窗。GitHub 在部分网络下连不上，因此依次尝试 raw → gh-proxy → jsDelivr，
>   并可在设置里填自己的镜像。
> - **签名**：密钥与口令都不在仓库里。本地放 `keystore.properties`（已 gitignore），
>   CI 用环境变量 `BYD_KEYSTORE_FILE` / `BYD_KEYSTORE_PASSWORD` / `BYD_KEY_ALIAS` /
>   `BYD_KEY_PASSWORD`。两个都没有时退回 debug 签名并警告 —— fork 之后照样能编译。
> - Release 包**关闭 R8 混淆**（`minifyEnabled false`）：无法真机回归时，混淆导致的反射失效
>   （Jackson / Koin / Retrofit）是运行期静默故障，风险高于收益。
>
> 上游项目本身托管在 GitLab，GitHub 上的 `ultrasonic/ultrasonic` 只是个「已迁移」空壳。
> 以下为上游原始说明。

Ultrasonic is free and open-source music streaming Android client for
[Subsonic][subsonic] [API][subapi] (version 1.7.0 or higher) compatible
servers.

## Help wanted

We currently don't have that much time to spend developing Subsonic, so any
contributions or active developers are always welcomed.
Have a look at [CONTRIBUTING](CONTRIBUTING.md) to get started.

## Download

App is available to download at following stores:

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="70">](https://play.google.com/store/apps/details?id=org.moire.ultrasonic)
[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="70">](https://f-droid.org/packages/org.moire.ultrasonic/)
[<img src="https://ultrasonic.gitlab.io/assets/img/get-it-on-gitlab.png" alt="Get it on GitLab" height="70">](https://gitlab.com/ultrasonic/ultrasonic/-/releases)

**Warning**: All three versions (Google Play, F-Droid and the APKs) are not
compatible (not signed by the same key)! You must uninstall one to install
the other, which will delete all your data.

If you want to use the version downloaded from F-Droid or from GitLab with
**Android Auto**, you must enable Unknown Sources as it is described in
[this wiki page][wikiaa].

## Bugs and issues

First, see if your issue haven’t been yet reported [here][issues], otherwise
open [a new issue][newissue].

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md).

## Supported (tested) Subsonic API implementations

- [Subsonic][subsonic]
- [Airsonic-Advanced][airsonic]
- [Supysonic][supysonic]
- [Ampache][ampache]

Other *Subsonic API* implementations should work as well as long as they
follow API [documentation][subapi].

## License

This software is licensed under the terms of the GNU General Public License
version 3 (GPLv3).

Full text of the license is available in the [LICENSE](LICENSE) file and
[online][gpl3].

[wikiaa]: https://gitlab.com/ultrasonic/ultrasonic/-/wikis/Using-Ultrasonic-with-Android-Auto
[issues]: https://gitlab.com/ultrasonic/ultrasonic/-/issues
[newissue]: https://gitlab.com/ultrasonic/ultrasonic/-/issues/new
[subsonic]: http://www.subsonic.org/
[subapi]: http://www.subsonic.org/pages/api.jsp
[airsonic]: https://github.com/airsonic-advanced/airsonic-advanced
[supysonic]: https://github.com/spl0k/supysonic
[ampache]: https://ampache.org/
[gpl3]: https://opensource.org/licenses/gpl-3.0.html
