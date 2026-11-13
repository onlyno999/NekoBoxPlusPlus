# NekoBox++

在官方 NekoBox for Android 1.4.2 基础上做的增强版：只加功能，不动官方的使用习惯。

- **独立软件**：包名 `com.onlyno999.nekoboxpp`、独立签名，可与官方版、其他改版同时安装，互不覆盖
- **下载**：[本仓库 Releases](https://github.com/onlyno999/NekoBoxPlusPlus/releases)（认准 `NekoBox-<版本>-arm64-v8a.apk`）
- **核心**：sing-box 定制分支（曲线兼容、XHTTP 传输、分流记账补丁），其余与官方同源

## 相比官方版新增了什么

- **节点按国家自动分区**：每个订阅分组的节点按出口国家归成一区（国旗＋中文国名＋数量），点标题展开/收起；一键测速后自动刷新分区
- **分区负载均衡（通用线）**：全前端只有一个「自动中」——点亮哪个分区，哪个池就是通用线，没写路由规则的流量都走它；点亮新的自动顶掉旧的，连接立刻切到新池最优；每 10 分钟按延迟在池内重选最优，使用中的节点连续 2 次无数据自动在池内换线；自动中的分区里节点不可手动点选
- **路由规则绑池**：每条路由规则可以单独绑定某个（分组，国家）池子，各走各的线路，与前台通用线无关，也不需要先在主页点亮「自动」
- **分流流量记账**：核心层补了记账——走池子的流量记到实际承运的那条节点上，节点卡片和底部总流量都算得上（官方版本只能给单节点规则记账）
- **分区顺序可调**：长按分区标题上下拖动排序，排好就锁死，节点数量变化也不会自己重排；「未检测」固定在最后
- **XHTTP 传输**：支持 xhttp/splithttp 节点与订阅，也能在手动添加里直接建 XHTTP 配置
- **WebDAV 备份**：工具→备份里可填 WebDAV 地址一键上传/下载配置备份；备份流量强制直连（不走自身隧道），坚果云地址填到 `/dav/` 根目录会自动落到 `NekoBox++` 子文件夹
- **常用预置**：连接测试链接默认 gstatic generate_204，远程/直连 DNS 默认阿里 223.5.5.5，分组标签栏恒显示（官方版只有一个分组时隐藏）

## 版本记录

- **v1.4.6（2026-11-13）**：① 承接 1.4.3 全部内容：删除补选（在用节点被删当场换同组最快）、分流池改选（池里在跑的节点被删/被订阅替掉后自动刷新改选）、检查更新只查本仓库、图标整幅完整展示、文档站上线。② 悬浮通知标题改为测出来的区域名在前、节点名在后。③ WebDAV 备份按日期存（nekoboxpp_backup_日期_时间.json）、下载时可选恢复哪一天，也可在页面上勾选删除服务器上的旧备份；WebDAV 设置区默认折叠，点开才显示地址与账号密码。④ 路由页默认三条规则默认开启：谷歌 Play 商店规则（完整域名单，走代理）排在中国域名、中国 IP 两条直连规则之前，商店不再卡 99%（老用户升级后自动补齐）。⑤ 快捷开关磁贴改用简笔 N 图标。

---

以下为上游官方 README 原文。

# NekoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/onlyno999/NekoBoxPlusPlus)](https://github.com/onlyno999/NekoBoxPlusPlus/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

sing-box / universal proxy toolchain for Android.

一款使用 sing-box 的 Android 通用代理软件.

## 下载 / Downloads

[![GitHub All Releases](https://img.shields.io/github/downloads/onlyno999/NekoBoxPlusPlus/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/Matsuridayo/NekoBoxForAndroid/releases)

[GitHub Releases 下载](https://github.com/Matsuridayo/NekoBoxForAndroid/releases)

**Google Play 版本自 2024 年 5 月起已被第三方控制，为非开源版本，请不要下载。**

**The Google Play version has been controlled by a third party since May 2024 and is a non-open
source version. Please do not download it.**

## 更新日志 & Telegram 发布频道 / Changelog & Telegram Channel

https://t.me/nekbox999

## 项目主页 & 文档 / Homepage & Documents

https://onlyno999.github.io/NekoBoxPlusPlus/

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

请到[这里](https://onlyno999.github.io/NekoBoxPlusPlus/nb4a-plugin/)下载插件以获得完整的代理支持.

Please visit [here](https://onlyno999.github.io/NekoBoxPlusPlus/nb4a-plugin/) to download plugins for full proxy
supports.

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## 捐助 / Donate

<details>

如果这个项目对您有帮助, 可以通过捐赠的方式帮助我们维持这个项目.

捐赠满等额 50 USD 可以在「[捐赠榜](https://mtrdnt.pages.dev/donation_list)」显示头像, 如果您未被添加到这里,
欢迎联系我们补充.

Donations of 50 USD or more can display your avatar on
the [Donation List](https://mtrdnt.pages.dev/donation_list). If you are not added here, please
contact us to add it.

USDT TRC20

`TNZYEoTHuriimnJSjBY6mreraMcrgSzNKw`

XMR

`5iDCcJrCqeqfHWhjgr8u7jUUvrqMr4srRHHDUeViTmC9`

</details>

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
