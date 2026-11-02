import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { FolderKanban, Search, UserRound } from 'lucide-react';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import { ariaKeyShortcuts, useShortcutSettings } from '../../lib/shortcuts';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { StatusBadge } from '../../ui/Badge';
import { Kbd } from '../../ui/Kbd';
import styles from './SidebarSearch.module.css';

export const SIDEBAR_SEARCH_ID = 'sidebar-search';

const supportsPopover = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype;

interface Option {
  id: string;
  group: 'Issues' | 'Projects' | 'People' | 'More';
  label: string;
  leading: ReactNode;
  trailing?: ReactNode;
  go: () => Promise<void>;
}

/**
 * Inline search in the sidebar (WAI-ARIA combobox): results for issues, projects and people
 * appear in a list right under the field while typing; ↑/↓ choose, Enter opens, Esc closes.
 * The list lives in the top layer so the scrolling sidebar can't clip it.
 */
export function SidebarSearch({ org, onNavigate }: { org: string; onNavigate: () => void }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const listId = useId();
  const fieldRef = useRef<HTMLLabelElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { activeBindingFor } = useShortcutSettings();
  const focusKeys = activeBindingFor('focusSearch');
  const term = query.trim();
  const debounced = useDebouncedValue(term, 150);
  const results = useQuery({
    queryKey: keys.globalSearch(org, debounced),
    enabled: debounced.length > 0,
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/search', { params: { path: { org }, query: { q: debounced } } })),
    staleTime: 10_000,
  });
  const data = debounced && debounced === term ? results.data : undefined;
  const searching = term.length > 0 && (debounced !== term || results.isFetching) && !data;

  const options: Option[] = [];
  if (data) {
    for (const issue of data.issues) {
      options.push({
        id: `issue-${issue.key}`,
        group: 'Issues',
        label: issue.title,
        leading: <span className={styles.key}>{issue.key}</span>,
        trailing: issue.status ? <StatusBadge name={issue.status} category={issue.statusCategory ?? 'TODO'} /> : undefined,
        go: () => navigate({ to: '/$org/issue/$issueKey', params: { org, issueKey: issue.key } }),
      });
    }
    for (const project of data.projects) {
      options.push({
        id: `project-${project.key}`,
        group: 'Projects',
        label: project.name,
        leading: <FolderKanban size={14} aria-hidden="true" />,
        trailing: <span className={styles.key}>{project.key}</span>,
        go: () => navigate({ to: '/$org/p/$key', params: { org, key: project.key } }),
      });
    }
    for (const person of data.people) {
      options.push({
        id: `person-${person.id}`,
        group: 'People',
        label: person.displayName,
        leading: <UserRound size={14} aria-hidden="true" />,
        trailing: <span className={styles.key}>@{person.handle}</span>,
        go: () => navigate({ to: '/$org/search', params: { org }, search: { assignee: person.id } }),
      });
    }
  }
  if (term) {
    options.push({
      id: 'all',
      group: 'More',
      label: `See all issues matching “${term}”`,
      leading: <Search size={14} aria-hidden="true" />,
      go: () => navigate({ to: '/$org/search', params: { org }, search: { q: term } }),
    });
  }
  const showList = open && term.length > 0;
  const activeOption = active >= 0 && active < options.length ? options[active] : undefined;

  const close = useCallback(() => {
    setOpen(false);
    setActive(-1);
  }, []);

  const choose = (option: Option) => {
    setQuery('');
    close();
    inputRef.current?.blur();
    onNavigate();
    void option.go();
  };

  const place = useCallback(() => {
    const field = fieldRef.current;
    const list = listRef.current;
    if (!field || !list) {
      return;
    }
    const rect = field.getBoundingClientRect();
    const width = Math.min(Math.max(rect.width, 352), window.innerWidth - 16);
    list.style.width = `${String(width)}px`;
    const rtl = getComputedStyle(field).direction === 'rtl';
    const left = rtl ? rect.right - width : rect.left;
    list.style.left = `${String(Math.max(8, Math.min(left, window.innerWidth - width - 8)))}px`;
    list.style.top = `${String(rect.bottom + 6)}px`;
    list.style.maxHeight = `${String(Math.max(160, window.innerHeight - rect.bottom - 16))}px`;
  }, []);

  useEffect(() => {
    const list = listRef.current;
    if (!showList || !list) {
      return undefined;
    }
    if (supportsPopover && !list.matches(':popover-open')) {
      list.showPopover();
    }
    place();
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!fieldRef.current?.contains(target) && !list.contains(target)) {
        close();
      }
    };
    const onMove = () => {
      place();
    };
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
  }, [showList, place, close]);

  useEffect(() => {
    if (activeOption) {
      document.getElementById(`${listId}-${activeOption.id}`)?.scrollIntoView({ block: 'nearest' });
    }
  }, [activeOption, listId]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        setOpen(true);
        setActive((i) => (options.length === 0 ? -1 : (i + 1) % options.length));
        break;
      case 'ArrowUp':
        event.preventDefault();
        setOpen(true);
        setActive((i) => (options.length === 0 ? -1 : i <= 0 ? options.length - 1 : i - 1));
        break;
      case 'Enter': {
        const target = activeOption ?? options.find((o) => o.id === 'all');
        if (target) {
          event.preventDefault();
          choose(target);
        }
        break;
      }
      case 'Escape':
        if (showList) {
          event.preventDefault();
          close();
        } else if (query) {
          event.preventDefault();
          setQuery('');
        }
        break;
      case 'Tab':
        close();
        break;
      default:
    }
  };

  let lastGroup: Option['group'] | null = null;
  return (
    <>
      <label ref={fieldRef} className={styles.field}>
        <Search size={15} aria-hidden="true" />
        <input
          ref={inputRef}
          id={SIDEBAR_SEARCH_ID}
          type="search"
          role="combobox"
          className={styles.input}
          placeholder="Search"
          aria-label="Search issues, projects and people"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList && activeOption ? `${listId}-${activeOption.id}` : undefined}
          aria-keyshortcuts={ariaKeyShortcuts(focusKeys)}
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onFocus={() => {
            setOpen(true);
          }}
          onKeyDown={onKeyDown}
        />
        {focusKeys && !query && <Kbd binding={focusKeys} className={styles.keys} />}
      </label>
      <div
        ref={listRef}
        id={listId}
        role="listbox"
        aria-label="Search results"
        className={styles.results}
        popover={supportsPopover ? 'manual' : undefined}
        hidden={!showList}
      >
        {searching && <p className={styles.status}>Searching…</p>}
        {data && options.length === 1 && <p className={styles.status}>No issues, projects or people match “{term}”.</p>}
        {options.map((option, index) => {
          const heading = option.group !== lastGroup && option.group !== 'More' ? option.group : null;
          lastGroup = option.group;
          return (
            <div key={option.id} className={option.group === 'More' ? styles.moreRow : undefined}>
              {heading && (
                <div className={styles.heading} role="presentation">
                  {heading}
                </div>
              )}
              <div
                id={`${listId}-${option.id}`}
                role="option"
                aria-selected={index === active}
                tabIndex={-1}
                className={styles.option}
                onPointerDown={(event) => {
                  // Keep focus in the field so the list stays open until the choice is made.
                  event.preventDefault();
                }}
                onPointerMove={() => {
                  setActive(index);
                }}
                onClick={() => {
                  choose(option);
                }}
                // Keyboard choice normally goes through the combobox (↑/↓, Enter); this covers a focused row.
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    choose(option);
                  }
                }}
              >
                {/* Spaces keep the accessible name readable ("WEB-1 Fix login"); flex ignores them visually. */}
                <span className={styles.leading}>{option.leading}</span>{' '}
                <span className={styles.label}>{option.label}</span>
                {option.trailing && (
                  <>
                    {' '}
                    <span className={styles.trailing}>{option.trailing}</span>
                  </>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <span className="visually-hidden" aria-live="polite">
        {showList && data ? `${String(options.length - 1)} results` : ''}
      </span>
    </>
  );
}
