import { Check, ChevronDown } from 'lucide-react';
import {
  Children,
  Fragment,
  isValidElement,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type FocusEvent,
  type KeyboardEvent,
  type ReactElement,
  type ReactNode,
  type Ref,
  type SelectHTMLAttributes,
} from 'react';
import styles from './Field.module.css';

interface Option {
  value: string;
  label: string;
  disabled: boolean;
}

/** Text of an option's children (strings, numbers and nested arrays), like the DOM's option.text. */
function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(textOf).join('');
  }
  if (isValidElement<{ children?: ReactNode }>(node)) {
    return textOf(node.props.children);
  }
  return '';
}

/** Flattens <option> children, including arrays from .map() and fragments. */
function readOptions(children: ReactNode): Option[] {
  const out: Option[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) {
      return;
    }
    const element = child as ReactElement<{ children?: ReactNode; value?: string | number; disabled?: boolean }>;
    if (element.type === Fragment) {
      out.push(...readOptions(element.props.children));
    } else if (element.type === 'option') {
      const label = textOf(element.props.children);
      out.push({ value: element.props.value === undefined ? label : String(element.props.value), label, disabled: Boolean(element.props.disabled) });
    }
  });
  return out;
}

const supportsPopover = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype;

type NativeProps = Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id' | 'multiple'>;

interface SelectControlProps extends NativeProps {
  id: string;
  describedBy: string | undefined;
  invalid: true | undefined;
  selectRef?: Ref<HTMLSelectElement> | undefined;
}

/**
 * A designed single-choice dropdown (WAI-ARIA select-only combobox). A hidden native <select>
 * stays the source of truth: choices are written to it and a real change event is dispatched,
 * so callers keep receiving ordinary onChange events with e.target.value. The list opens in the
 * browser's top layer (popover), so clipped cards and modal dialogs cannot cut it off.
 */
export function SelectControl({ id, describedBy, invalid, selectRef, children, value, defaultValue, disabled, onBlur, className, ...rest }: SelectControlProps) {
  const options = readOptions(children);
  const [uncontrolled, setUncontrolled] = useState(() => (defaultValue === undefined ? undefined : String(defaultValue)));
  const current = value === undefined ? (uncontrolled ?? options[0]?.value ?? '') : String(value);
  const selectedIndex = options.findIndex((o) => o.value === current);
  const selected = options[selectedIndex];

  const triggerRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const typeahead = useRef({ text: '', timer: 0 });

  const close = useCallback((refocus: boolean) => {
    setOpen(false);
    if (refocus) {
      triggerRef.current?.focus();
    }
  }, []);

  const choose = (index: number) => {
    const option = options[index];
    const select = wrapRef.current?.querySelector('select');
    if (!option || option.disabled || !select) {
      return;
    }
    if (option.value !== current) {
      // Write through the native setter so React's change tracking sees a real user change.
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(select, option.value);
      select.dispatchEvent(new Event('change', { bubbles: true }));
      setUncontrolled(option.value);
    }
    close(true);
  };

  const place = useCallback(() => {
    const trigger = triggerRef.current;
    const list = listRef.current;
    if (!trigger || !list) {
      return;
    }
    const rect = trigger.getBoundingClientRect();
    list.style.minWidth = `${String(rect.width)}px`;
    const height = list.offsetHeight;
    const width = list.offsetWidth;
    const below = window.innerHeight - rect.bottom;
    const openUp = below < height + 8 && rect.top > below;
    const rtl = getComputedStyle(trigger).direction === 'rtl';
    const left = rtl ? rect.right - width : rect.left;
    list.style.left = `${String(Math.max(8, Math.min(left, window.innerWidth - width - 8)))}px`;
    list.style.top = `${String(openUp ? Math.max(8, rect.top - height - 4) : rect.bottom + 4)}px`;
  }, []);

  useEffect(() => {
    const list = listRef.current;
    if (!open || !list) {
      return undefined;
    }
    if (supportsPopover && !list.matches(':popover-open')) {
      list.showPopover();
    }
    place();
    list.focus({ preventScroll: true });
    const onPointer = (event: PointerEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) {
        close(false);
      }
    };
    const onMove = () => { place(); };
    document.addEventListener('pointerdown', onPointer);
    window.addEventListener('resize', onMove);
    window.addEventListener('scroll', onMove, true);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      window.removeEventListener('resize', onMove);
      window.removeEventListener('scroll', onMove, true);
      if (supportsPopover && list.matches(':popover-open')) {
        list.hidePopover();
      }
    };
  }, [open, place, close]);

  useEffect(() => {
    if (open && active >= 0) {
      listRef.current?.querySelector<HTMLElement>(`[data-index="${String(active)}"]`)?.scrollIntoView({ block: 'nearest' });
    }
  }, [open, active]);

  const enabledFrom = (start: number, step: 1 | -1): number => {
    for (let i = start; i >= 0 && i < options.length; i += step) {
      if (!options[i]?.disabled) {
        return i;
      }
    }
    return -1;
  };

  const openList = (startAt?: number) => {
    if (disabled) {
      return;
    }
    setActive(startAt ?? (selectedIndex >= 0 ? selectedIndex : enabledFrom(0, 1)));
    setOpen(true);
  };

  const jumpTo = (key: string) => {
    window.clearTimeout(typeahead.current.timer);
    typeahead.current.text += key.toLowerCase();
    typeahead.current.timer = window.setTimeout(() => { typeahead.current.text = ''; }, 600);
    const from = active >= 0 ? active : 0;
    const order = [...options.keys()].map((i) => (from + (typeahead.current.text.length === 1 ? 1 : 0) + i) % options.length);
    return order.find((i) => !options[i]?.disabled && options[i]?.label.toLowerCase().startsWith(typeahead.current.text)) ?? -1;
  };

  const onTriggerKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
      event.preventDefault();
      openList();
    } else if (event.key.length === 1 && /\S/.test(event.key)) {
      const index = jumpTo(event.key);
      if (index >= 0) {
        openList(index);
      }
    }
  };

  const onListKey = (event: KeyboardEvent<HTMLDivElement>) => {
    switch (event.key) {
      case 'ArrowDown': {
        event.preventDefault();
        const next = enabledFrom(active + 1, 1);
        if (next >= 0) setActive(next);
        break;
      }
      case 'ArrowUp': {
        event.preventDefault();
        const next = enabledFrom(active - 1, -1);
        if (next >= 0) setActive(next);
        break;
      }
      case 'Home':
      case 'PageUp':
        event.preventDefault();
        setActive(enabledFrom(0, 1));
        break;
      case 'End':
      case 'PageDown':
        event.preventDefault();
        setActive(enabledFrom(options.length - 1, -1));
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        choose(active);
        break;
      case 'Escape':
        event.preventDefault();
        close(true);
        break;
      case 'Tab':
        close(false);
        break;
      default:
        if (event.key.length === 1 && /\S/.test(event.key)) {
          const index = jumpTo(event.key);
          if (index >= 0) setActive(index);
        }
    }
  };

  /** Focus left the control entirely (not just moved between the trigger and its list). */
  const onPartBlur = (event: FocusEvent<HTMLElement>) => {
    if (!wrapRef.current?.contains(event.relatedTarget)) {
      if (open) {
        setOpen(false);
      }
      onBlur?.(event as unknown as FocusEvent<HTMLSelectElement>);
    }
  };

  return (
    <div ref={wrapRef} className={`${styles.selectWrap ?? ''} ${className ?? ''}`}>
      <select
        ref={selectRef}
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        value={current}
        disabled={disabled}
        onChange={() => undefined}
        {...rest}
      >
        {children}
      </select>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        role="combobox"
        className={styles.select}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-describedby={describedBy}
        aria-invalid={invalid}
        disabled={disabled}
        onClick={() => { if (open) { close(false); } else { openList(); } }}
        onKeyDown={onTriggerKey}
        onBlur={onPartBlur}
      >
        <span className={selected ? styles.selectValue : `${styles.selectValue ?? ''} ${styles.selectPlaceholder ?? ''}`}>{selected?.label ?? ''}</span>
      </button>
      <ChevronDown className={styles.chevron} size={14} aria-hidden="true" data-open={open || undefined} />
      <div
        ref={listRef}
        id={listId}
        role="listbox"
        tabIndex={-1}
        popover={supportsPopover ? 'manual' : undefined}
        className={styles.listbox}
        hidden={!supportsPopover && !open}
        aria-labelledby={id}
        aria-activedescendant={open && active >= 0 ? `${listId}-${String(active)}` : undefined}
        onKeyDown={onListKey}
        onBlur={onPartBlur}
      >
        {open &&
          options.map((option, index) => (
            <div
              key={`${option.value}-${String(index)}`}
              id={`${listId}-${String(index)}`}
              role="option"
              data-index={index}
              aria-selected={index === selectedIndex}
              aria-disabled={option.disabled || undefined}
              className={styles.option}
              data-active={index === active || undefined}
              onPointerMove={() => { if (!option.disabled && active !== index) setActive(index); }}
              onPointerUp={() => { choose(index); }}
            >
              <span className={styles.optionLabel}>{option.label || ' '}</span>
              {index === selectedIndex && <Check size={14} aria-hidden="true" className={styles.optionCheck} />}
            </div>
          ))}
      </div>
    </div>
  );
}
