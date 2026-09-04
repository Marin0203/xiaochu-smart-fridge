import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.smartfridge.app',
  appName: '小厨',
  webDir: 'webapp',
  // 2026-09-04 渲染源根修: 必须让 Capacitor 从本地服务加载(热更页面), 否则默认走 https://localhost 内嵌资产页
  // 运行时镜像: android/app/src/main/assets/capacitor.config.json (cap sync 未配置, 手工同步, 两处需一致)
  server: {
    url: 'http://127.0.0.1:8890/',
    androidScheme: 'https',
    cleartext: true,
  },
  android: {
    allowMixedContent: false,
  },
};

export default config;
