import rawAppletConfig from '../../firebase-applet-config.json';

export interface ResolvedFirebaseConfig {
  projectId: string;
  appId: string;
  apiKey: string;
  authDomain: string;
  firestoreDatabaseId: string;
  storageBucket: string;
  messagingSenderId: string;
  measurementId?: string;
  oAuthClientId?: string;
  recaptchaSiteKey?: string;
}

declare const __FIREBASE_RESOLVED_CONFIG__: Partial<ResolvedFirebaseConfig> | undefined;

const buildTimeConfig: Partial<ResolvedFirebaseConfig> =
  typeof __FIREBASE_RESOLVED_CONFIG__ !== 'undefined' && __FIREBASE_RESOLVED_CONFIG__
    ? __FIREBASE_RESOLVED_CONFIG__
    : {};

const projectId = (buildTimeConfig.projectId || rawAppletConfig.projectId || '').trim();

export const firebaseConfig: ResolvedFirebaseConfig = {
  projectId,
  appId: (buildTimeConfig.appId || rawAppletConfig.appId || '').trim(),
  apiKey: (buildTimeConfig.apiKey || rawAppletConfig.apiKey || '').trim(),
  authDomain: (
    buildTimeConfig.authDomain ||
    rawAppletConfig.authDomain ||
    (projectId ? `${projectId}.firebaseapp.com` : '')
  ).trim(),
  firestoreDatabaseId: (
    buildTimeConfig.firestoreDatabaseId ||
    rawAppletConfig.firestoreDatabaseId ||
    ''
  ).trim(),
  storageBucket: (
    buildTimeConfig.storageBucket ||
    rawAppletConfig.storageBucket ||
    (projectId ? `${projectId}.firebasestorage.app` : '')
  ).trim(),
  messagingSenderId: (
    buildTimeConfig.messagingSenderId ||
    rawAppletConfig.messagingSenderId ||
    ''
  ).trim(),
  measurementId: (buildTimeConfig.measurementId ?? rawAppletConfig.measurementId ?? '').trim(),
  oAuthClientId: (buildTimeConfig.oAuthClientId ?? rawAppletConfig.oAuthClientId ?? '').trim(),
  recaptchaSiteKey: (
    buildTimeConfig.recaptchaSiteKey ??
    rawAppletConfig.recaptchaSiteKey ??
    ''
  ).trim(),
};

export default firebaseConfig;
