# ScreenCapture - 屏幕圈选截图工具

Android悬浮窗截图应用，支持任意形状圈选截取屏幕内容。

## 功能

- 悬浮按钮，可拖动位置
- 点击悬浮按钮进入截图模式
- 手指任意形状圈选屏幕区域
- 保存圈选区域为PNG图片（透明背景）
- 图片保存至 Pictures/ScreenCapture 目录

## 编译

### GitHub Actions 自动编译

1. Fork 此仓库
2. 进入 Actions 标签页
3. 点击 "Android CI" 工作流
4. 点击 "Run workflow" 手动触发编译
5. 编译完成后在 Artifacts 下载 APK

### 本地编译

```bash
# 需要 JDK 17 和 Android SDK
./gradlew assembleDebug
# APK 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

- `SYSTEM_ALERT_WINDOW` - 悬浮窗权限
- `FOREGROUND_SERVICE` - 前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` - 屏幕录制服务
- 存储权限 - 保存截图

## 使用说明

1. 安装并打开应用
2. 点击"启动服务"按钮
3. 授予悬浮窗权限和屏幕录制权限
4. 点击屏幕上的悬浮按钮开始截图
5. 用手指圈选任意形状的区域
6. 点击保存按钮保存图片

## 系统要求

- Android 8.0 (API 26) 或更高版本
