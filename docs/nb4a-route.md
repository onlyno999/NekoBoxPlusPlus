# 路由

路由决定哪些流量走代理、哪些直连、哪些屏蔽。在「路由」页面管理规则，自上而下依次匹配。

## 常用设置

- **出口**：代理（走当前连接）、绕过（直连）、屏蔽（拦截）
- **条件**：域名、域名后缀、IP 段、GeoIP / GeoSite 分类等

## 分流绑池（NekoBox++ 特性）

出口选「选择配置」后，可以把这条规则绑到：

- 某个具体节点，或
- 某个（分组＋国家）的节点池（点分区的「自动」）

绑池后这条规则的流量在池内自动选最快的节点，和主连接走哪条互不影响。每条规则可以绑不同的池。

## 谷歌 Play 商店下载规则（中国规则必加）

开了「中国域名 / 中国 IP 直连」这类规则后，Play 商店会无法下载、或下载到 99% 卡住不动——商店用的几个谷歌域名被判成了直连。

解决办法：自己加一条规则，把下面这些域名指到**代理**，并把这条规则放在中国规则**前面**：

- 类型：`domain`（域名）
- 内容（整段复制粘贴）：

```
googleapis.com,connectivitycheck.gstatic.com,www.google.com,play-fe.googleapis.com,play-lh.googleusercontent.com,playstoregatewayadapter-pa.googleapis.com,services.googleapis.cn,xn--ngstr-lra8j.com,play.googleapis.com,mtalk.google.com
```

- 出口：代理（也可以绑一个节点池）

加完重连一次，商店就能正常下载了。不加这条、又开着中国域名/中国 IP 规则的话，商店就是下不了。

> 注：1.4.6 起这三条规则已经内置在 App 里、默认开启（路由页可见），不用再手动添加。

## AI 服务分流规则

想让 Gemini、ChatGPT、Claude 这些 AI 服务固定走代理（也可以绑一个指定地区的节点池，如美国池），自己加一条规则：

- 类型：`domain`（域名）
- 内容（整段复制粘贴）：

```
gemini.google.com,aistudio.google.com,ai.google.dev,notebooklm.google.com,deepmind.google,generativelanguage.googleapis.com,chatgpt.com,openai.com,oaistatic.com,oaiusercontent.com,sora.com,claude.ai,anthropic.com,claudeusercontent.com,perplexity.ai,x.ai,grok.com,poe.com,huggingface.co,copilot.microsoft.com
```

- 出口：代理（也可以绑一个节点池）

这些域名按后缀匹配，子域名自动一起生效。加完重连一次即可。
