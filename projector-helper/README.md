# 投影仪端：对焦助手（projector-helper）

装在投影仪上的辅助应用。手机遥控器以蓝牙键盘身份发出指定的功能键，它通过无障碍服务截获这些按键，再调用投影仪系统自带的功能。

- 包名：`dev.imeiyou.focushelper`
- 系统要求：Android 9（大眼橙 C5 Ultra 的系统版本）
- 不依赖第三方库，不申请网络权限，不读取任何界面内容

## 功能键对照

| 按键 | 功能 | 调用方式 |
|---|---|---|
| F12 | 深度对焦（等同原装遥控器长按对焦键） | 启动 `FocusService`，参数 `from=remote_long_press` |
| F10 | 自动梯形（自动梯形 + 对焦，等同设置菜单里的"智能校正"） | 绑定 `CommonApiService`，调用 `autoKeystone()` |
| F9 | 打开手动梯形校正界面 | 启动 `ProjectorCallerService`，动作 `KEYSTONE_CORRECTION` |
| F8 | MEMC 开关（与游戏模式互斥；手机端已改用 Alt+6…9 档位，F8 保留） | 读写内容提供者 `com.bkdisplay.system.provider` 的 `memc` |
| F7 | 游戏模式开关（与 MEMC 互斥） | 读写同一内容提供者的 `game_mode` |
| F6 | 打开画面尺寸界面 | 启动 `ProjectorCallerService`，动作 `SCREEN_SIZE` |
| F4 | 打开画面位移界面 | 启动 `ProjectorCallerService`，动作 `SCREEN_DISPLACE` |
| F1 / F2 | 光源亮度 +5 / −5（按住每 150ms 调一次） | 读写内容提供者的 `back_light_progress`，范围限制为 10–100 |
| Alt+1 … Alt+5 | 亮度设为 30 / 50 / 70 / 85 / 100 | 同上 |
| Alt+6 … Alt+9 | MEMC 设为 关 / 低 / 中 / 高（开启时关闭游戏模式） | 读写内容提供者的 `memc` |

- F5 不使用：投影仪的 `Generic.kl` 里 key 63 没有映射。
- 游戏模式与 MEMC 的互斥顺序与系统菜单一致：开游戏模式前先把 MEMC 设为 0；开 MEMC 前先关 PC 模式和游戏模式。
- 开游戏模式时会记下当时的 MEMC 档位，关游戏模式时恢复；用 F8 开 MEMC 时使用上次记下的档位，从没记过时用 1（低）。
- 亮度最低限制为 10，防止误操作导致黑屏。

## 编译

1. 用 Android Studio 打开 `projector-helper` 文件夹，等待 Gradle 同步完成。
   - 提示 Gradle JVM 不兼容时，选 **Use JVM 21**。
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
3. 生成的文件在 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装前：确认按键映射

不同固件的按键映射可能不同，先确认：

```bat
adb shell "grep -nE '^key (2|3|4|5|6|7|8|9|10|56|59|60|62|64|65|66|67|68|88) ' /system/usr/keylayout/Generic.kl"
```

应该输出：

```
key 2     1
key 3     2
key 4     3
key 5     4
key 6     5
key 7     6
key 8     7
key 9     8
key 10    9
key 56    ALT_LEFT
key 59    F1
key 60    F2
key 62    F4
key 64    F6
key 65    F7
key 66    F8
key 67    F9
key 68    F10
key 88    F12
```

有不一致的，说明你的固件和作者测试的不同，请先提 Issue，不要直接使用。

## 安装与启用

### 1. 安装

```bat
adb connect <投影仪IP>:5555
adb install -r app-debug.apk
```

### 2. 先用测试界面确认功能可用

这一步不需要开启无障碍服务。

```bat
adb logcat -c
adb shell am start -n dev.imeiyou.focushelper/.MainActivity
```

用遥控器依次点界面上的按钮，每点一个等投影仪动作完成再点下一个。然后查看日志：

```bat
adb shell "logcat -d | grep -iE 'FocusHelper|FocusService|algExec|autoKeystone|KeystoneDialog|CommonApiService'"
```

| 按钮 | 成功的标志 |
|---|---|
| 深度对焦 | 投影仪开始对焦；日志有 `algExecAutoFocus` |
| 自动梯形 | 画面先校正再对焦；日志有 `called autoKeystone()` 和 `algExecAutoKeystone` |
| 手动梯形校正 | 弹出手动梯形校正界面 |
| 画面尺寸 / 画面位移 | 弹出对应的调整界面 |
| 亮度 +5 / −5 | 屏幕提示当前亮度，画面亮度变化 |
| MEMC / 游戏模式 | 屏幕提示新状态，例如"游戏模式：开（MEMC 已关）"；可到系统菜单确认 |

> **注意：** 自动梯形会重新计算并覆盖当前的梯形参数，效果和在设置菜单里点"智能校正"相同。

### 3. 开启无障碍服务

先查看当前已开启的无障碍服务：

```bat
adb shell settings get secure enabled_accessibility_services
```

- 输出是 `null` 或空：

  ```bat
  adb shell settings put secure enabled_accessibility_services dev.imeiyou.focushelper/dev.imeiyou.focushelper.FocusKeyService
  adb shell settings put secure accessibility_enabled 1
  ```

- 已经有内容（假设是 `X`）：用冒号连起来，**不要覆盖原有内容**：

  ```bat
  adb shell settings put secure enabled_accessibility_services "X:dev.imeiyou.focushelper/dev.imeiyou.focushelper.FocusKeyService"
  adb shell settings put secure accessibility_enabled 1
  ```

再打开一次测试界面，顶部应显示"运行中"。

### 4. 授权自动恢复（必做）

实测投影仪开机时，系统自带的语音助手有时会把无障碍服务清单整个改写成只剩它自己，对焦助手因此被关掉。授权一次后，对焦助手会自动把自己加回去：

```bat
adb shell pm grant dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
```

- 授权重启后仍然有效；卸载后失效，覆盖安装不失效。
- 检查时机：开机完成时、开机后 30 秒和 2 分钟；服务运行期间清单一旦被改掉，1 秒后加回；应用被覆盖安装后；测试界面上的"立即修复无障碍服务"按钮。
- 清单里有但服务没有运行时（开机后的两次定时检查和"立即修复"按钮），会先把自己从清单移除再加回，让系统重新启动服务。
- 原理、做不到的情况和检查命令见 [常见问题](../README.md#guard)。
- 恢复时**保留清单里原有的其他服务**，只把对焦助手追加回去。

这个权限只用于改写无障碍服务清单这一项设置。如果你不想授权，也可以不做这一步，但开机后功能键可能失效，需要手动重新执行第 3 步。

测试界面顶部显示的是真实状态：

| 显示 | 含义 |
|---|---|
| 运行中 | 服务已被系统绑定，按键截获有效 |
| 设置里已开启，但服务没有运行 | 清单里有，但系统没有启动服务；点"立即修复"（会移除再加回），或重启 |
| 未开启 | 清单里没有 |

**停用对焦助手：** 先撤销授权，否则它会自己恢复：

```bat
adb shell pm revoke dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
```

然后从清单里移除，或直接卸载。

## 调试：模拟按键

> **不要用 `adb shell input keyevent` 测试。** `input` 命令注入的按键会绕过系统的输入过滤器，无障碍服务截获不到，日志里只会出现 `Unhandled key`，这和服务是否正常无关。

改用 `sendevent`，借原装遥控器的输入设备写入原始按键，效果和真实按键相同。先找遥控器的设备文件（按一下原装遥控器任意键唤醒）：

```bat
adb shell "getevent -p | grep -B1 'OBE R3'"
```

假设结果是 `/dev/input/event4`，发一次 F12（Linux 键号 88）：

```bat
adb logcat -c
adb shell "sendevent /dev/input/event4 1 88 1; sendevent /dev/input/event4 0 0 0; sendevent /dev/input/event4 1 88 0; sendevent /dev/input/event4 0 0 0"
adb shell "logcat -d | grep -iE 'FocusHelper|Unhandled key'"
```

日志里应有 `FocusHelper` 的触发记录，且没有 `Unhandled key`。其他键把 88 换成对应的 Linux 键号：

| 按键 | Linux 键号 | 功能 |
|---|---|---|
| F12 | 88 | 深度对焦 |
| F10 | 68 | 自动梯形 |
| F9 | 67 | 手动梯形校正 |
| F8 | 66 | MEMC 开关 |
| F7 | 65 | 游戏模式开关 |
| F6 | 64 | 画面尺寸 |
| F4 | 62 | 画面位移 |
| F1 | 59 | 亮度 +5 |
| F2 | 60 | 亮度 −5 |

重启后设备编号可能变化，需要重新查。

## 行为说明

- 功能键只在**第一次按下**时触发（亮度加减除外）；按住产生的重复事件和松开事件都会被吞掉，不传给前台应用。
- 防重复触发：深度对焦 3 秒、自动梯形 5 秒、其他功能 1 秒内的重复按键会被忽略。
- 其他所有按键一律放行。
- 截获不区分来源：投影仪上接了实体键盘的话，它的这些 F 键和 Alt+1…9 也会被对焦助手接管。
- 不要手动启动系统里的 `GyroscopeService`：在 C5 Ultra 上它会导致自动梯形异常，需要完全关机再开机才能恢复。
