import firebaseRulesPlugin from '@firebase/eslint-plugin-security-rules';

export default [
  {
    ignores: ['dist/**/*', 'node_modules/**/*', 'firebase/**/*'],
  },
  firebaseRulesPlugin.configs['flat/recommended'],
];
