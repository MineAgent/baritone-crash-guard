# MGBaritoneCrashGuard — 抑制 Baritone 造成的退出崩溃（Fabric / Minecraft 26.3）

Baritone 的 worker 线程是**非 daemon** 的。正常退出时渲染线程返回后，这些线程还活着，
JVM 因此无法结束；15 秒后原版的 post-main 看门狗会写一份
`Client shutdown from post-main` 崩溃报告，并 `System.exit(-8)`（进程退出码 248）。

本模组在渲染线程结束时把 Baritone 自己拥有的执行器停掉，于是：

* **不再有这份崩溃报告**，正常退出就是正常退出，退出码 `0`；
* 不调用 `System.exit`、不 halt、不碰别的模组的线程——JVM 在自己的非 daemon 线程都结束后
  自然退出；如果还有**别人**的线程在泄漏，看门狗照样如实报告；
* 显式依赖 Baritone（`fabric.mod.json` 的 `depends.baritone`）：没装 Baritone 时 Fabric
  Loader 直接拒绝加载本模组，而不是运行时静默失效。

## 为什么需要单独一个模组

[MGHttpdProvider](https://github.com/MineAgent/HttpdProvider) 1.2.0 起只收拾**自己的**摊子：
退出时停掉自己的 HTTP 服务，绝不替别的模组兜底。它明确把 Baritone 的 worker pool 留给了
原版看门狗——因为那份报告是**真报告**，线程 dump 会点名是谁。问题在于：Baritone 这个 bug
在**每一次正常退出**都会触发，报告本身没有信息量，只会让退出码变成 248、在 `crash-reports/`
里堆文件。

所以把「Baritone 的退出收尾」拆成这个专门的模组：它只认 Baritone，只动 Baritone 的线程，
既可以和 MGHttpdProvider / MGBaritoneConnector 一起装，也可以单独装（本模组**不**依赖 3420
服务）。

## 崩溃现场

`crash-reports/crash-…-client.txt` 里是这样的：

```
Description: Client shutdown from post-main
java.lang.Error: Watchdog (Client shutdown from post-main)
    at net.minecraft.server.dedicated.ServerWatchdog.createWatchdogCrashReport(...)
    at com.mojang.blaze3d.platform.ClientShutdownWatchdog.lambda$startShutdownWatchdog$0(...)

-- Thread Dump --
"pool-4-thread-1" … WAITING
    at java.util.concurrent.LinkedBlockingQueue.take(...)
    at knot//baritone.o$a.run(Unknown Source)
    at java.util.concurrent.ThreadPoolExecutor.runWorker(...)
"pool-4-thread-2" … TIMED_WAITING
    at java.lang.Thread.sleep(...)
    at knot//baritone.o.b(Unknown Source)
"pool-4-thread-3" … WAITING
    at java.util.concurrent.SynchronousQueue.take(...)
```

Baritone 1.20.0 的共享池是 `baritone.a` 的 `private static final ThreadPoolExecutor`
（core=4、`SynchronousQueue`、60 s keepAlive、**默认线程工厂 = 非 daemon**），上面跑着
`baritone.o$a`（区块缓存打包循环）和 `baritone.o.b`（每 30 秒/10 分钟一次的缓存 save）。
原版 `Main` 的看门狗只等 15 秒，而 `shutdown()` 不会打断正在 sleep 的任务，所以必须
`shutdownNow()`；反编译确认这两个任务捕获 `InterruptedException` 后直接 `return`，线程能
干净结束。

## 依赖

| 组件 | 是否必需 | 说明 |
| --- | --- | --- |
| [Baritone](https://github.com/cabaletta/baritone) | **必需** | 实测 **1.20.0**（`baritone-api-fabric-1.20.0.jar`）；`depends: baritone >=1.20.0` |
| Fabric Loader | **必需** | 0.20.1+ |
| Minecraft | **必需** | 26.3（`~26.3`） |
| Java | **必需** | 25+ |
| Fabric API / MGHttpdProvider | 不需要 | 本模组没有 mixin、没有 HTTP endpoint、没有 Fabric API 调用 |

Baritone **不打包**进本模组：`build.gradle` 里是 `compileOnly`，运行时由 Fabric Loader 从
`.minecraft/mods/` 加载真正的那份。

## 安装

1. 把 `baritone-api-fabric-1.20.0.jar` 和 `mgbaritonecrashguard-1.0.0.jar` 一起放进
   `.minecraft/mods/`（26.3 实例的 mods 目录）。
2. 启动后日志里会有：

   ```
   [Render thread/INFO]: MGBaritoneCrashGuard armed: Baritone's executors will be stopped when the client exits
   ```

3. 正常退出（暂停菜单 →「保存并退回到标题屏幕」→「退出游戏」）后：

   ```
   [Render thread/INFO]: client exited, stopping Baritone's executors
   [mgbaritonecrashguard/INFO]: stopped 1 Baritone executor(s), so no Baritone thread can keep the JVM alive
   ```

   进程退出码 `0`，`crash-reports/` 不新增文件。

> 退出日志里可能还会出现两段 `InterruptedException` 的 `printStackTrace`（线程名
> `pool-N-thread-M`）。那是 **Baritone 自己的**中断处理（`baritone.o$a` / `baritone.o.b`
> 捕获后打印再返回），说明线程确实被中断、正在干净退出，不是本模组出错。

## 它到底做了什么

`ClientExitGuard` 是一个 **daemon** 线程，行为只有三步：

1. 惰性等待 `Minecraft.getInstance()/getRunningThread()`，`join()` 渲染线程（入口点在
   `Minecraft` 构造器里跑，那时拿不到线程，所以是轮询 + 延迟解析）；
2. 通过 `BaritoneAPI.getProvider().getAllBaritones()` 拿到 Baritone 实例；
3. `ExecutorScan` 从这些实例出发，**有界地**反射收集 Baritone 拥有的 `ExecutorService`，
   然后 `shutdownNow()`，最多等 3 秒确认终止。

`ExecutorScan` 的边界是刻意收窄的：

* 只跟随运行时类名以 `baritone.` 开头的对象，绝不进入 Minecraft 对象、字符串、装箱值；
* 每个访问到的 Baritone 类的 **static** 字段都检查一遍——共享池就在 `baritone.a` 的静态字段上；
* 跟随实例字段；因为 Baritone 把 process 注册在普通 `List` 里，额外走**一层** `Iterable`
  （每容器最多 128 个元素），Map / 数组 / 执行器内部不进入；
* 上限 4096 个对象、6 层、256 个池；任何反射失败都只跳过，不抛异常；
* 只停**收集到的**池，别的线程一个都不碰。

这样能覆盖崩溃报告里那个共享池；elytra 场景下 `baritone.et` / `baritone.eq` 的执行器
如果已经创建且可达，也会一并停掉。

### 与「强制退出」的区别

MGHttpdProvider **1.1.1** 曾经在退出时无条件 `System.exit(0)`，那会把别人的非 daemon 线程
一起静音。本模组**不**这么做：

* 不 `System.exit`、不 `Runtime.halt`；Baritone 的池停掉之后，JVM 会自己结束；
* 如果还有第三个模组的线程在泄漏，JVM 仍然退不掉，15 秒后看门狗仍然会写报告——那份报告是
  真的，本模组不去掩盖它；
* 真崩溃（`System.exit` 已被别处调用、SIGTERM 等）时，做与不做都不会改变退出码。

### 已知限制

* 只处理**从当前 Baritone 实例可达的**执行器。将来 Baritone 改内部结构、静态池不再能被反射
  找到时，会打一行 WARN（`no Baritone executor found …`），崩溃报告保留——这是如实失败；
* `baritone.et` / `baritone.eq` 的 elytra 执行器只在对应场景创建，是否可达取决于 Baritone
  的内部对象图；
* 如果 Baritone 的某个任务不响应中断，本模组最多等 3 秒后如实记录「有几个池没结束」，
  看门狗仍可能开火；
* 只支持 26.3（Baritone 1.20.0）。26.2 实例用的是 Baritone 1.19.0，需要另出一个构建。

## 构建

需要 JDK 25（Minecraft 26.3 要求）。26.1 起官方代码不再混淆，Loom 不需要 mappings 配置。

```bash
./gradlew build
# -> build/libs/mgbaritonecrashguard-1.0.0.jar
```

`build.gradle` 的 `downloadBaritone` 会把钉死版本的 Baritone API jar 下载到 `build/deps/`
（gitignored）并校验 SHA-256，只用于编译，不会打进产物。

## 验证

### 脱离游戏（不需要 Minecraft / Baritone）

`tools/stub/baritone/stub/` 里的桩类模仿 Baritone 的对象形状（静态池、实例字段池、藏在
`List` 里的池，以及一个非 Baritone 的池），验证「该找的找到、不该停的不停」：

```bash
javac --release 25 -encoding UTF-8 -d build/verify \
  src/main/java/com/example/mgbcg/ExecutorScan.java \
  tools/VerifyScan.java \
  tools/stub/baritone/stub/*.java tools/stub/com/example/foreign/*.java
java -cp build/verify VerifyScan        # 全部通过才退 0
```

### 实机（26.3）

把两个 jar 放进 26.3 实例的 `mods/`，进入任意存档，然后按正常流程退出（暂停菜单 →
「保存并退回到标题屏幕」→「退出游戏」）。

如果同时装了 [mcctl](https://github.com/MineAgent/mcctl)（`/ctl`），也可以远程走完这个流程：

```bash
curl -X POST --data-binary 'esc' http://127.0.0.1:3420/ctl
curl -o /tmp/pause.png http://127.0.0.1:3420/ctl/prtsc
# 看截图，点「保存并退回到标题屏幕」，回到标题后再点「退出游戏」
curl -X POST --data-binary 'mouse goto <x> <y>' http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'mouse left'          http://127.0.0.1:3420/ctl
```

验收：进程退出码 `0`、`crash-reports/` 不新增文件、日志里出现上面那两行、没有
`Client shutdown from post-main`。

## 目录

```
src/main/java/com/example/mgbcg/
  MGBaritoneCrashGuardMod.java  Fabric 客户端入口：装退出 watcher
  ClientExitGuard.java          daemon watcher：等渲染线程结束 → 停 Baritone 执行器
  BaritonePools.java            通过 BaritoneAPI 取实例，委托 ExecutorScan，负责日志
  ExecutorScan.java             纯 JDK 的有界反射扫描 + shutdownNow（可离线测）
src/main/resources/fabric.mod.json
tools/VerifyScan.java           离线自检
tools/stub/baritone/stub/*.java 仅测试用的假 Baritone 对象，不进 jar
tools/stub/com/example/foreign/ForeignPool.java  仅测试用的“别人的线程”，不进 jar
```

## 版本

* **1.0.0** — 第一版：退出时 `shutdownNow()` Baritone 的执行器（静态共享池 + 可达的
  `ExecutorService`），不再产生 `Client shutdown from post-main` 崩溃报告，退出码 `0`。
  无 mixin、不改 Baritone 行为、不调用 `System.exit`。

## 许可证

LGPL-3.0-only：完整文本见 [`LICENSE`](LICENSE)，其中引用的 GPL-3.0 见
[`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)。源码文件头部标有
`SPDX-License-Identifier: LGPL-3.0-only`。
