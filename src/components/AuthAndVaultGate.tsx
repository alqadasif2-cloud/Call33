import React, { useState } from 'react';
import { friendlyErrorMessageAr, SessionBootstrapState } from '../core/firebase';
import { AuthSessionResult, UsernameAuth } from '../core/usernameAuth';

interface UsernameAuthGateProps {
  onAuthenticated: (session: AuthSessionResult) => void;
  sessionNotice?: SessionBootstrapState | null;
}

export const UsernameAuthGate: React.FC<UsernameAuthGateProps> = ({
  onAuthenticated,
  sessionNotice,
}) => {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState<string>(() => UsernameAuth.getSavedUsername());
  const [password, setPassword] = useState<string>('');
  const [busy, setBusy] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const isRegister = mode === 'register';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);

    try {
      const session = isRegister
        ? await UsernameAuth.register(username, password)
        : await UsernameAuth.login(username, password);
      onAuthenticated(session);
    } catch (err) {
      setError(friendlyErrorMessageAr(err));
    } finally {
      setBusy(false);
    }
  };

  const toggleMode = () => {
    if (busy) return;
    setMode((prev) => (prev === 'login' ? 'register' : 'login'));
    setPassword('');
    setError(null);
  };

  return (
    <div className="mx-auto flex min-h-[calc(100vh-9rem)] max-w-md flex-col justify-center px-6 py-12">
      <div className="rounded-xl border border-slate-200 bg-white p-7 sm:p-8">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">
          {isRegister ? 'إنشاء حساب' : 'تسجيل الدخول'}
        </h1>

        {sessionNotice?.sessionExpiredReset && !error && (
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-xs leading-relaxed text-amber-950">
            انتهت صلاحية الجلسة السابقة. يرجى تسجيل الدخول للمتابعة.
          </div>
        )}

        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          <div>
            <label htmlFor="auth-username" className="block text-sm font-semibold text-slate-800">
              اسم المستخدم
            </label>
            <input
              id="auth-username"
              type="text"
              dir="ltr"
              required
              minLength={3}
              maxLength={32}
              disabled={busy}
              autoComplete="username"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                if (error) setError(null);
              }}
              className="mt-1.5 w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-left text-sm text-slate-900 focus:border-[#1F3A5F] focus:outline-none disabled:bg-slate-50"
            />
          </div>

          <div>
            <label htmlFor="auth-password" className="block text-sm font-semibold text-slate-800">
              كلمة المرور
            </label>
            <input
              id="auth-password"
              type="password"
              dir="ltr"
              required
              minLength={8}
              maxLength={128}
              disabled={busy}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (error) setError(null);
              }}
              className="mt-1.5 w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-left text-sm text-slate-900 focus:border-[#1F3A5F] focus:outline-none disabled:bg-slate-50"
            />
          </div>

          {error && (
            <div
              role="alert"
              className="rounded-lg border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm leading-relaxed text-red-700"
            >
              {error}
            </div>
          )}

          <div className="pt-2">
            <button
              type="submit"
              disabled={busy || username.trim().length === 0 || password.length === 0}
              className="w-full rounded-lg bg-[#1F3A5F] px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-[#162a47] disabled:opacity-50 whitespace-nowrap"
            >
              {busy ? 'جارٍ التحقق...' : isRegister ? 'إنشاء حساب' : 'تسجيل الدخول'}
            </button>
          </div>
        </form>

        <div className="mt-5 border-t border-slate-100 pt-4 text-center">
          <button
            type="button"
            disabled={busy}
            onClick={toggleMode}
            className="text-sm font-semibold text-[#1F3A5F] hover:underline disabled:opacity-50 whitespace-nowrap"
          >
            {isRegister ? 'تسجيل الدخول' : 'إنشاء حساب جديد'}
          </button>
        </div>
      </div>
    </div>
  );
};
