import { Check } from 'lucide-react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import styles from './Menu.module.css';

export interface MenuItem {
  label: string;
  /** Secondary line shown under the label (for example a role). */
  description?: string;
  icon?: ReactNode;
  /** Marks the current choice with a check. */
  selected?: boolean;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

interface MenuProps {
  /** Renders the trigger; spread the props onto a button. */
  trigger: (props: {
    ref: (element: HTMLButtonElement | null) => void;
    'aria-haspopup': 'menu';
    'aria-expanded': boolean;
    'aria-controls': string;
    onClick: () => void;
    onKeyDown: (event: React.KeyboardEvent) => void;
  }) => ReactNode;
  items: MenuItem[];
  align?: 'start' | 'end';
  /** Stretch the trigger to the container width. */
  block?: boolean;
  /** Open above the trigger (for triggers near the bottom of the viewport). */
  side?: 'bottom' | 'top';
}

/** Action menu following the WAI-ARIA menu button pattern (arrow keys, Home/End, Escape). */
export function Menu({ trigger, items, align = 'end', block = false, side = 'bottom' }: MenuProps) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const [triggerElement, setTriggerElement] = useState<HTMLButtonElement | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const container = useRef<HTMLDivElement>(null);

  const focusItem = (index: number) => {
    const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
    if (!buttons || buttons.length === 0) {
      return;
    }
    buttons[(index + buttons.length) % buttons.length]?.focus();
  };

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    focusItem(0);
    const onPointer = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointer);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
    };
  }, [open]);

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) {
      triggerElement?.focus();
    }
  };

  const onListKeyDown = (event: React.KeyboardEvent) => {
    const buttons = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []);
    const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        focusItem(current + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        focusItem(current - 1);
        break;
      case 'Home':
        event.preventDefault();
        focusItem(0);
        break;
      case 'End':
        event.preventDefault();
        focusItem(buttons.length - 1);
        break;
      case 'Escape':
        event.preventDefault();
        close(true);
        break;
      case 'Tab':
        close(false);
        break;
      default:
    }
  };

  return (
    <div className={block ? `${styles.container ?? ''} ${styles.block ?? ''}` : styles.container} ref={container}>
      {trigger({
        ref: setTriggerElement,
        'aria-haspopup': 'menu',
        'aria-expanded': open,
        'aria-controls': id,
        onClick: () => {
          setOpen((value) => !value);
        },
        onKeyDown: (event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            setOpen(true);
          }
        },
      })}
      {open && (
        <div id={id} role="menu" tabIndex={-1} ref={listRef} className={`${styles.menu ?? ''} ${styles[align] ?? ''} ${side === 'top' ? (styles.top ?? '') : ''}`} onKeyDown={onListKeyDown}>
          {items.map((item, index) => (
            <button
                key={`${String(index)}-${item.label}`}
                type="button"
                role="menuitem"
                aria-current={item.selected ? true : undefined}
                className={item.danger ? `${styles.item ?? ''} ${styles.danger ?? ''}` : styles.item}
                disabled={item.disabled}
                onClick={() => {
                  close(true);
                  item.onSelect();
                }}
              >
                {item.icon && <span className={styles.icon}>{item.icon}</span>}
                <span className={styles.text}>
                  <span className={styles.label}>{item.label}</span>
                  {item.description && <span className={styles.description}>{item.description}</span>}
                </span>
                {item.selected && <Check className={styles.check} size={14} aria-hidden="true" />}
              </button>
          ))}
        </div>
      )}
    </div>
  );
}
