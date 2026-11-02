# 大眼橙 C5 Ultra 手机蓝牙遥控器

[English summary below](#english)

把 Android 手机变成大眼橙 C5 Ultra 投影仪的蓝牙遥控器：复刻原装遥控器的按键，并加入原装遥控器上没有的投影功能快捷键（深度对焦、自动梯形、手动梯形、画面尺寸、画面位移、游戏模式、MEMC、光源亮度）。

> **非官方项目。** 与大眼橙（OBE）及其关联公司无任何关系。项目只调用投影仪系统里已有的功能，不修改系统文件，也不包含任何厂商代码。使用风险自负。

## 组成

| 目录 | 装在哪里 | 作用 |
|---|---|---|
| [`phone-remote`](phone-remote/) | 手机（Android 12+） | 以蓝牙键盘 + 鼠标身份连接投影仪，提供遥控界面和触控板 |
| [`projector-helper`](projector-helper/) | 投影仪 | 无障碍服务，截获手机发来的 F1–F12 功能键，调用对应的投影仪功能 |

只装手机端也能用基础按键（方向、确认、返回、主页、菜单、设置、音量、静音、电源、触控板）；投影功能需要两端都装。

```
手机 ──蓝牙 HID（键盘/消费者/鼠标）──▶ 投影仪
                                       ├─ 基础按键：系统直接处理
                                       └─ F 功能键：对焦助手截获 → 调用系统功能
```

## 兼容性

| 项目 | 已测试 |
|---|---|
| 投影仪 | 大眼橙 C5 Ultra，Android 9，固件版本：1.0.38 |
| 手机 | Android 12 及以上，需要系统支持蓝牙 HID Device |

其他大眼橙型号可能部分可用，但按键映射和系统接口都可能不同，**需要实机测试**。如果你在其他型号或固件上试过，欢迎在 Issue 里反馈结果。

## 功能

| 按键 | 说明 | 需要对焦助手 |
|---|---|---|
| 方向键 / 确认 / 返回 / 主页 / 菜单 / 设置 | 与原装遥控器一致 | 否 |
| 电源 | 短按弹出关机菜单；按住 1～3 秒松手取消；按满 3 秒直接关机 | 否 |
| 音量 + / − / 静音 | 连接后手机侧边音量键也会控制投影仪音量 | 否 |
| 触控板 | 单指移动光标、轻点点击、双指滚动 | 否 |
| 深度对焦 | 等同原装遥控器长按对焦键 | 是 |
| 自动梯形 | 自动梯形 + 对焦，等同设置菜单里的"智能校正" | 是 |
| 手动梯形 / 画面尺寸 / 画面位移 | 直接打开对应的系统调整界面 | 是 |
| 游戏模式 / MEMC | 游戏模式一键开关；MEMC 可选 关 / 低 / 中 / 高。两者互斥（与系统菜单逻辑一致） | 是 |
| 亮度 | 底部面板：±5 调节（可按住）和 30/50/70/85/100 预设 | 是 |

## 快速开始
<img width="480" height="270" alt="projector_screen" src="https://github.com/user-attachments/assets/cd061031-c6ce-4858-9b86-6cd468024c36" />

1. **投影仪端**：按 [projector-helper/README.md](projector-helper/README.md) 安装、确认按键映射、开启无障碍服务并授权自动恢复。


<img width="270" height="557" alt="phone_shot" src="https://github.com/user-attachments/assets/c6b6bf2e-8daf-46f3-9dfd-62a1b3b4b7a7" />

2. **手机端**：安装后按 [phone-remote/README.md](phone-remote/README.md) 配对。

> **最常见的问题：按键错乱。** 投影仪会缓存每台蓝牙设备的 HID 描述符。如果手机以前和投影仪配对过（无论用的是什么应用），必须**两边都删除配对**后重新配对，否则会出现主页变成鼠标左键、静音变成返回之类的问题。

## 常见问题详解

执行前先连接投影仪：

```bat
adb connect <投影仪IP>:5555
```

<a id="guard"></a>
### 1. 投影功能键没反应 / 重启后失效

授权自动恢复（只需一次）：

```bat
adb shell pm grant dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
```

然后在投影仪上打开对焦助手，点"立即修复无障碍服务"，顶部显示"运行中"即可。

检查状态：

```bat
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb shell "dumpsys package dev.imeiyou.focushelper | grep WRITE_SECURE_SETTINGS"
```

- 第 1 条输出应包含 `dev.imeiyou.focushelper/dev.imeiyou.focushelper.FocusKeyService`
- 第 2 条应为 `1`
- 第 3 条应有 `granted=true`

在系统设置里强行停止过对焦助手的，需要手动打开一次对焦助手。

停用对焦助手（先撤销授权，再卸载）：

```bat
adb shell pm revoke dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
adb uninstall dev.imeiyou.focushelper
```

<a id="a2dp"></a>
### 2. 投影仪弹出蓝牙音乐界面

查手机蓝牙地址（也可在手机"关于手机 → 状态信息"里查看）：

```bat
adb shell "dumpsys bluetooth_manager | grep -A 10 -i 'bonded devices'"
```

关闭这台手机的蓝牙音频（地址字母大写、保留冒号）：

```bat
adb shell settings put global bluetooth_a2dp_src_priority_AA:BB:CC:DD:EE:FF 0
```

确认（输出 `0` 即生效，手机蓝牙关闭再打开一次）：

```bat
adb shell settings get global bluetooth_a2dp_src_priority_AA:BB:CC:DD:EE:FF
```

恢复：

```bat
adb shell settings delete global bluetooth_a2dp_src_priority_AA:BB:CC:DD:EE:FF
```

**每次重新配对后都要重新执行。**
<a id="repair"></a>
### 3. 按键错乱

1. 投影仪蓝牙设置里删除手机；
2. 手机蓝牙设置里删除投影仪；
3. 遥控应用里**长按"连接"** → "让手机可被发现（首次配对用）"；
4. 投影仪蓝牙设置里搜索并配对手机；
5. 重新执行第 2 节的命令。
<a id="connect"></a>
### 4. 手机点"连接"一直失败

| 应用显示 | 处理 |
|---|---|
| 注册失败 / 未注册：可能有其他蓝牙键盘/鼠标应用正在占用 | 强行停止其他蓝牙键盘/鼠标/遥控类应用，关闭再打开手机蓝牙 |
| 连接请求失败 | 确认投影仪已开机；没配对过的按第 3 节配对 |
| 无法使用蓝牙 HID 功能 | 手机系统不支持，无法解决 |
连接经常自己断开：把遥控应用的电池设置改为"不限制"。
<a id="input"></a>
### 5. 自动梯形突然失效

- 完全关机再开机（不是待机）。
- 关闭系统的快速游戏模式。
- 不要手动执行 `am startservice` 启动 `GyroscopeService`。

## 实现说明

- 手机端使用 Android 的 `BluetoothHidDevice` API，一个复合 HID 描述符包含键盘（报告 ID 1）、消费者控制（报告 ID 2）、鼠标（报告 ID 3），见 [`HidDescriptor.java`](phone-remote/app/src/main/java/dev/imeiyou/projectorremote/HidDescriptor.java)。
- 投影仪对手机这类外接键盘使用 `/system/usr/keylayout/Generic.kl`。功能键选用了其中有映射、投影仪系统本身不占用的 F1、F2、F4、F6–F10、F12。
- 对焦助手调用的接口都是投影仪系统设置应用（`com.obe.settings`）中已有的服务和内容提供者，具体见 [projector-helper/README.md](projector-helper/README.md)。这些都不是公开接口，**固件升级后可能失效**。

## 许可证

Copyright (C) 2026 Imeiyou

本项目以 [GPL-3.0](LICENSE) 发布：可以自由使用、修改和分发，包括商业用途；但发布修改版或包含本项目代码的软件时，必须以 GPL-3.0 公开全部源代码，并保留原作者署名。
 v0.1.0 及之前的版本以 MIT 许可证发布。
---

<a id="english"></a>

## English

An unofficial Bluetooth remote for the **OBE (大眼橙) C5 Ultra** projector (Android 9), made of two apps:

- **`phone-remote`** (Android 12+ phone): acts as a Bluetooth HID keyboard + consumer control + mouse via `BluetoothHidDevice`. Replicates the stock remote (D-pad, OK, Back, Home, Menu, Settings, volume, mute, power with short/long press) and adds a touchpad.
- **`projector-helper`** (projector): an accessibility service that intercepts F-keys sent by the phone and triggers built-in projector functions — deep focus, auto keystone, manual keystone, screen size, screen shift, game mode / MEMC (mutually exclusive), and light-source brightness.

Basic keys work with the phone app alone; projector functions need both apps.

**Important:** the projector caches each Bluetooth device's HID descriptor. If your phone was ever paired with the projector before, unpair on **both** sides and pair again, otherwise keys will be misinterpreted.

The projector-side calls rely on non-public services in the vendor's settings app and may break after a firmware update. Only tested on the C5 Ultra. Not affiliated with OBE. Licensed under GPL-3.0.Licensed under GPL-3.0. Versions up to v0.1.0 were released under MIT.

Documentation is in Chinese; see each subfolder's README for details.
