import { createRoot } from 'react-dom/client';
import { runPrebootFirebaseAuthGuard } from './core/prebootAuthGuard';
import './index.css';

void (async () => {
  await runPrebootFirebaseAuthGuard();
  const { default: App } = await import('./App.tsx');
  createRoot(document.getElementById('root')!).render(<App />);
})();
