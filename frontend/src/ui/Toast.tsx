import { CircleAlert, CircleCheck, X } from 'lucide-react';
import { createContext, use, useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import styles from './Toast.module.css';

type ToastTone = 'success' | 'danger';

interface ToastItem {
  id: number;
  tone: ToastTone;
  message: string;
}

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

/** Transient confirmations and failures, announced politely (errors assertively). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const next = useRef(1);

  const dismiss = useCallback((id: number) => {
    setItems((current) => current.filter((item) => item.id !== id));
  }, []);

  const push = useCallback(
    (tone: ToastTone, message: string) => {
      const id = next.current++;
      setItems((current) => [...current.slice(-3), { id, tone, message }]);
      window.setTimeout(() => {
        dismiss(id);
      }, tone === 'danger' ? 8000 : 4000);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(
    () => ({ success: (m) => { push('success', m); }, error: (m) => { push('danger', m); } }),
    [push],
  );

  return (
    <ToastContext value={api}>
      {children}
      <div className={styles.region} aria-live="polite" aria-relevant="additions">
        {items.map((item) => (
          <div key={item.id} className={`${styles.toast ?? ''} ${styles[item.tone] ?? ''}`} role={item.tone === 'danger' ? 'alert' : 'status'}>
            {item.tone === 'danger' ? <CircleAlert size={16} aria-hidden="true" /> : <CircleCheck size={16} aria-hidden="true" />}
            <span className={styles.message}>{item.message}</span>
            <button
              type="button"
              className={styles.close}
              aria-label="Dismiss"
              onClick={() => {
                dismiss(item.id);
              }}
            >
              <X size={14} aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext>
  );
}

export function useToast(): ToastApi {
  const api = use(ToastContext);
  if (!api) {
    throw new Error('useToast must be used inside ToastProvider');
  }
  return api;
}
