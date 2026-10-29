import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import fs from 'fs';
import path from 'path';
import {defineConfig} from 'vite';

interface GoogleServicesJson {
  project_info?: {
    project_number?: string;
    project_id?: string;
    storage_bucket?: string;
  };
  client?: Array<{
    client_info?: {
      mobilesdk_app_id?: string;
    };
    oauth_client?: Array<{
      client_id?: string;
    }>;
    api_key?: Array<{
      current_key?: string;
    }>;
  }>;
}

function readJsonIfExists<T>(filePath: string): T | null {
  try {
    if (!fs.existsSync(filePath)) return null;
    return JSON.parse(fs.readFileSync(filePath, 'utf-8')) as T;
  } catch {
    return null;
  }
}

function resolveFirebaseBuildConfig(rootDir: string): Record<string, string> {
  const appletCfg =
    readJsonIfExists<Record<string, string>>(path.resolve(rootDir, 'firebase-applet-config.json')) ??
    {};

  const googleServicesCandidates = [
    path.resolve(rootDir, 'google-services.json'),
    path.resolve(rootDir, 'backup-app/google-services.json'),
    path.resolve(rootDir, 'viewer-app/google-services.json'),
  ];

  let gsCfg: GoogleServicesJson | null = null;
  for (const candidate of googleServicesCandidates) {
    gsCfg = readJsonIfExists<GoogleServicesJson>(candidate);
    if (gsCfg) break;
  }

  const firebaseRc = readJsonIfExists<{projects?: {default?: string}}>(
    path.resolve(rootDir, '.firebaserc')
  );

  const firstClient = gsCfg?.client?.[0];
  const gsApiKey = firstClient?.api_key?.[0]?.current_key ?? '';
  const gsProjectId = gsCfg?.project_info?.project_id ?? '';
  const gsSenderId = gsCfg?.project_info?.project_number ?? '';
  const gsStorageBucket = gsCfg?.project_info?.storage_bucket ?? '';
  const gsAppId = firstClient?.client_info?.mobilesdk_app_id ?? '';
  const gsOAuthClientId = firstClient?.oauth_client?.[0]?.client_id ?? '';

  const envApiKey = (
    process.env.VITE_FIREBASE_API_KEY ||
    process.env.FIREBASE_API_KEY ||
    ''
  ).trim();

  const projectId = (
    appletCfg.projectId ||
    gsProjectId ||
    firebaseRc?.projects?.default ||
    ''
  ).trim();

  const apiKey = (appletCfg.apiKey || gsApiKey || envApiKey).trim();
  const messagingSenderId = (appletCfg.messagingSenderId || gsSenderId).trim();
  const storageBucket = (
    appletCfg.storageBucket ||
    gsStorageBucket ||
    (projectId ? `${projectId}.firebasestorage.app` : '')
  ).trim();
  const authDomain = (
    appletCfg.authDomain ||
    (projectId ? `${projectId}.firebaseapp.com` : '')
  ).trim();

  return {
    projectId,
    appId: (appletCfg.appId || gsAppId).trim(),
    apiKey,
    authDomain,
    firestoreDatabaseId: (appletCfg.firestoreDatabaseId || '').trim(),
    storageBucket,
    messagingSenderId,
    measurementId: (appletCfg.measurementId || '').trim(),
    oAuthClientId: (appletCfg.oAuthClientId || gsOAuthClientId).trim(),
    recaptchaSiteKey: (appletCfg.recaptchaSiteKey || '').trim(),
  };
}

export default defineConfig(() => {
  const resolvedFirebaseConfig = resolveFirebaseBuildConfig(__dirname);

  return {
    plugins: [react(), tailwindcss()],
    define: {
      __FIREBASE_RESOLVED_CONFIG__: JSON.stringify(resolvedFirebaseConfig),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 3000,
      allowedHosts: true as const,
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
