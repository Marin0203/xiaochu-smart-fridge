import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.smartfridge.app',
  appName: '小厨',
  webDir: 'webapp',
  android: {
    allowMixedContent: false,
  },
};

export default config;
