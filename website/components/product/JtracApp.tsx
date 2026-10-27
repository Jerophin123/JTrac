'use client';

import { Bell, CalendarRange, ChevronsUpDown, FolderKanban, House, KanbanSquare, LayoutDashboard, List, Search, Settings } from 'lucide-react';
import { useState } from 'react';
import { Logo } from '../Logo';
import { demoIssues, type DemoIssue, statusById } from './data';
import { Avatar, Badge, OrgMark } from './primitives';
import shell from './styles/AppShell.module.css';
import base from './styles/Base.module.css';
import page from './styles/Page.module.css';
import sidebar from './styles/Sidebar.module.css';
import tabs from './styles/Tabs.module.css';
import { BoardView, IssueTable, IssueView, TimelineView } from './views';

type View = 'board' | 'list' | 'timeline';

const VIEWS: { id: View; label: string; icon: typeof List }[] = [
  { id: 'board', label: 'Board', icon: KanbanSquare },
  { id: 'list', label: 'List', icon: List },
  { id: 'timeline', label: 'Timeline', icon: CalendarRange },
];

const NAV = [
  { label: 'My work', icon: House },
  { label: 'Inbox', icon: Bell, count: 3 },
  { label: 'Issues', icon: Search },
  { label: 'Dashboards', icon: LayoutDashboard },
  { label: 'Projects', icon: FolderKanban },
  { label: 'Settings', icon: Settings },
];

const PROJECTS = [
  { key: 'JTRAC', name: 'Jtrac Platform', active: true },
  { key: 'WEB', name: 'Website' },
  { key: 'OPS', name: 'Operations' },
];

const PROJECT_NAME = 'Jtrac Platform';
const VIEWER = 'Maya Kapoor';

/** Static replica of the product sidebar (app/shell/Sidebar.tsx). Not interactive. */
function Sidebar() {
  return (
    <aside className={`${sidebar.sidebar} pointer-events-none select-none`} aria-hidden="true">
      <div className={sidebar.brandRow}>
        <span className={sidebar.brandLink}>
          <Logo height={22} role="presentation" />
        </span>
      </div>
      <div className={sidebar.orgButton}>
        <OrgMark name="Demo org" />
        <span className={sidebar.orgText}>
          <span className={sidebar.orgName}>Demo org</span>
          <span className={sidebar.orgRole}>Illustrative data</span>
        </span>
        <ChevronsUpDown size={14} />
      </div>
      <div className={sidebar.searchButton}>
        <Search size={15} />
        <span>Search</span>
        <kbd className={sidebar.kbd}>Ctrl K</kbd>
      </div>
      <nav className={sidebar.nav}>
        <ul className={sidebar.navList}>
          {NAV.map(({ label, icon: Icon, count }) => (
            <li key={label}>
              <span className={sidebar.navItem}>
                <Icon size={16} />
                {label}
                {count ? <span className={sidebar.count}>{count}</span> : null}
              </span>
            </li>
          ))}
        </ul>
      </nav>
      <section className={sidebar.section}>
        <p data-heading className={sidebar.sectionLabel}>Projects</p>
        <ul className={sidebar.navList}>
          {PROJECTS.map((p) => (
            <li key={p.key}>
              <span className={sidebar.navItem} data-status={p.active ? 'active' : undefined}>
                <span className={sidebar.projectKey}>{p.key}</span>
                <span className={sidebar.projectName}>{p.name}</span>
              </span>
            </li>
          ))}
        </ul>
      </section>
      <div className={sidebar.footer}>
        <div className={sidebar.user}>
          <Avatar name={VIEWER} />
          <span className={sidebar.userText}>
            <span className={sidebar.userName}>{VIEWER}</span>
            <span className={sidebar.userEmail}>@maya</span>
            <span className={sidebar.userRole}>Administrator</span>
          </span>
          <ChevronsUpDown size={14} />
        </div>
      </div>
    </aside>
  );
}

/**
 * Working replica of the Jtrac web app built from the product's own CSS Modules and demo
 * data. Visitors can switch Board / List / Timeline, open an issue and run its workflow
 * transitions. Everything is local state: nothing is saved and no server is contacted.
 */
export function JtracApp() {
  const [view, setView] = useState<View>('list');
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [issues, setIssues] = useState<DemoIssue[]>(demoIssues);
  const open = issues.find((i) => i.key === openKey) ?? null;

  const transition = (key: string, to: string) => {
    setIssues((all) =>
      all.map((i) =>
        i.key === key
          ? {
              ...i,
              statusId: to,
              history: [
                ...i.history,
                { who: VIEWER, what: `changed status ${statusById[i.statusId]!.name} → ${statusById[to]!.name}`, when: 'just now' },
              ],
            }
          : i,
      ),
    );
  };

  return (
    // The scope is the size container; the shell grid inside it responds to its width.
    <div className={base.scope}>
      <div className={shell.shell}>
        <header className={shell.mobileBar}>
          <Logo height={22} role="presentation" aria-hidden="true" />
        </header>
        <Sidebar />
        <div className={shell.main} aria-live="polite">
          {open ? (
            <IssueView
              issue={open}
              projectName={PROJECT_NAME}
              onBack={() => setOpenKey(null)}
              onTransition={(to) => transition(open.key, to)}
            />
          ) : (
            <div className={page.wide}>
              <header className={page.header}>
                <div className={page.heading}>
                  <div className={page.eyebrow}>
                    <span>Projects</span>
                    <span aria-hidden="true">/</span>
                    <span className={base.issueKey}>JTRAC</span>
                  </div>
                  <p data-heading className={page.title}>
                    <span className={base.row}>
                      {PROJECT_NAME}
                      <Badge tone="accent">Demo data</Badge>
                    </span>
                  </p>
                  <p className={page.description}>Illustrative issues for this preview, not issues from the repository.</p>
                </div>
              </header>
              <nav aria-label="Project views" className={tabs.nav}>
                <ul className={tabs.list}>
                  {VIEWS.map(({ id, label, icon: Icon }) => (
                    <li key={id}>
                      <button
                        type="button"
                        className={`${tabs.tab} cursor-pointer`}
                        data-status={view === id ? 'active' : undefined}
                        aria-current={view === id ? 'page' : undefined}
                        onClick={() => setView(id)}
                      >
                        <Icon size={15} aria-hidden="true" /> {label}
                      </button>
                    </li>
                  ))}
                </ul>
              </nav>
              {view === 'board' ? <BoardView issues={issues} onOpen={setOpenKey} /> : null}
              {view === 'list' ? <IssueTable issues={issues} onOpen={setOpenKey} /> : null}
              {view === 'timeline' ? <TimelineView issues={issues} onOpen={setOpenKey} /> : null}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
