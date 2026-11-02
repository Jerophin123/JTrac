import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, useNavigate, useParams } from '@tanstack/react-router';
import { MailWarning, Menu, WifiOff } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { orgQuery, orgsQuery } from '../../api/queries';
import { extractColorFromImage } from '../../lib/colorExtractor';
import { useShortcuts } from '../../lib/shortcuts';
import { readSidebarCollapsed, saveSidebarCollapsed } from '../../lib/sidebar';
import { publicConfigQueryOptions, sessionQueryKey, sessionQueryOptions, useIsServerAdmin } from '../../features/auth/session';
import { CreateIssueDialog } from '../../features/issues/CreateIssueDialog';
import { Button } from '../../ui/Button';
import { Logo } from '../../ui/Logo';
import { CommandPalette } from '../CommandPalette';
import { ShortcutsDialog } from '../ShortcutsDialog';
import { useRealtime } from '../realtime';
import styles from './AppShell.module.css';
import { Sidebar } from './Sidebar';
import { SIDEBAR_SEARCH_ID } from './SidebarSearch';

const NAV_ID = 'primary-navigation';

/** Authenticated application frame: floating sidebar + content. Rendered only with a session. */
export function AppShell() {
  const { data: session } = useQuery(sessionQueryOptions);
  const [navOpen, setNavOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(readSidebarCollapsed);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const signingOut = useRef(false);
  const { org, key } = useParams({ strict: false });
  const { data: orgDetail } = useQuery({
    ...orgQuery(org ?? ''),
    enabled: Boolean(org),
  });
  const { data: orgs } = useQuery(orgsQuery);
  const currentOrg = orgs?.find((o) => o.slug === org);
  const activeColor = orgDetail?.settings.color ?? currentOrg?.color;
  const activeLogo = orgDetail?.settings.logoUrl ?? currentOrg?.logoUrl;
  const activeName = orgDetail?.name ?? currentOrg?.name;
  const online = useOnline();
  const [extractedLogoColor, setExtractedLogoColor] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    if (!activeColor && activeLogo) {
      void extractColorFromImage(activeLogo).then((extracted) => {
        if (active && extracted) {
          setExtractedLogoColor(extracted);
        }
      });
    }
    return () => {
      active = false;
    };
  }, [activeColor, activeLogo]);

  const effectiveColor = activeColor ?? (activeLogo ? extractedLogoColor : null);

  // Organization theme: tokens.css derives every themed token from --org-color. Set through
  // the DOM (CSSOM), which the production CSP allows; an injected <style> would be blocked.
  useEffect(() => {
    const root = document.documentElement;
    if (effectiveColor) {
      root.style.setProperty('--org-color', effectiveColor.trim().toLowerCase());
      root.dataset.orgTheme = '';
    }
    return () => {
      root.style.removeProperty('--org-color');
      delete root.dataset.orgTheme;
    };
  }, [effectiveColor]);

  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>("link[rel~='icon']");
    if (link) {
      link.href = activeLogo ?? '/logo(2).svg';
    }
  }, [activeLogo]);

  useEffect(() => {
    if (activeName) {
      document.title = `${activeName} | JTRAC`;
    } else {
      document.title = 'JTRAC';
    }
  }, [activeName]);

  // The session can end while the shell is shown (expiry, server restart, sign-out in
  // another tab, account disabled): a refetch then yields null, so send the user to sign in.
  useEffect(() => {
    if (session === null && !signingOut.current) {
      void navigate({ to: '/login', search: { redirect: window.location.pathname + window.location.search }, replace: true });
    }
  }, [session, navigate]);


  const openPalette = () => {
    setPaletteOpen(true);
  };

  // "/" jumps into the sidebar search when it is on screen; otherwise (collapsed rail, closed
  // drawer) the command palette offers the same search.
  const focusSearch = () => {
    const field = document.getElementById(SIDEBAR_SEARCH_ID);
    if (field instanceof HTMLInputElement && field.offsetParent !== null && field.getClientRects().length > 0) {
      field.focus();
    } else {
      openPalette();
    }
  };

  const toggleCollapsed = () => {
    setCollapsed((value) => {
      saveSidebarCollapsed(!value);
      return !value;
    });
  };

  // Every keyboard shortcut (lib/shortcuts.ts); keys are customizable under Account › Keyboard shortcuts.
  useShortcuts({
    toggleSidebar: toggleCollapsed,
    showShortcuts: () => {
      setShortcutsOpen(true);
    },
    goAccount: () => void navigate({ to: '/account' }),
    ...(org
      ? {
          commandPalette: openPalette,
          focusSearch,
          createIssue: () => {
            setCreateOpen(true);
          },
          goMyWork: () => void navigate({ to: '/$org', params: { org } }),
          goInbox: () => void navigate({ to: '/$org/inbox', params: { org } }),
          goIssues: () => void navigate({ to: '/$org/search', params: { org } }),
          goDashboards: () => void navigate({ to: '/$org/dashboards', params: { org } }),
          goProjects: () => void navigate({ to: '/$org/projects', params: { org } }),
          goSettings: () => void navigate({ to: '/$org/settings', params: { org } }),
        }
      : {}),
  });

  const completeSignOut = async () => {
    signingOut.current = true;
    queryClient.setQueryData(sessionQueryKey, null);
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== sessionQueryKey[0] });
    await navigate({ to: '/login', search: { signedOut: true } });
  };

  useEffect(() => {
    if (!navOpen) {
      return undefined;
    }
    // Move focus into the drawer; the content behind it is inert while it is open.
    document.getElementById(NAV_ID)?.querySelector<HTMLElement>('a[href], button')?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpen(false);
        menuButton.current?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [navOpen]);

  if (!session) {
    return null;
  }

  const closeNav = () => {
    setNavOpen(false);
  };

  return (
    <div
      className={`${styles.shell ?? ''} theme-scope`}
      data-sidebar={collapsed ? 'collapsed' : undefined}
      style={
        effectiveColor
          ? ({
              '--color-accent': effectiveColor,
              '--color-focus': effectiveColor,
            } as React.CSSProperties)
          : undefined
      }
    >
      <a className={styles.skipLink} href="#main">
        Skip to content
      </a>

      <header className={styles.mobileBar}>
        <Button
          ref={menuButton}
          variant="ghost"
          size="sm"
          icon={<Menu size={18} aria-hidden="true" />}
          aria-label="Open navigation"
          aria-expanded={navOpen}
          aria-controls={NAV_ID}
          onClick={() => {
            setNavOpen(true);
          }}
        />
        <Logo height={24} />
      </header>

      <Sidebar
        id={NAV_ID}
        session={session}
        open={navOpen}
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
        onClose={closeNav}
        onSignedOut={completeSignOut}
        onOpenSearch={() => {
          closeNav();
          openPalette();
        }}
      />
      {navOpen && (
        <button type="button" className={styles.backdrop} aria-label="Close navigation" tabIndex={-1} onClick={closeNav} />
      )}

      <main id="main" className={styles.main} tabIndex={-1} inert={navOpen || undefined}>
        {!online && (
          <div className={styles.offline} role="status">
            <WifiOff size={16} aria-hidden="true" />
            You’re offline. Changes can’t be saved until the connection returns; what you see may be out of date.
          </div>
        )}
        <EmailPendingNotice />
        <Outlet />
      </main>

      <ShortcutsDialog
        open={shortcutsOpen}
        onClose={() => {
          setShortcutsOpen(false);
        }}
      />

      {org && (
        <>
          <OrgRealtime org={org} />
          <CommandPalette
            org={org}
            open={paletteOpen}
            onClose={() => {
              setPaletteOpen(false);
            }}
            onCreateIssue={() => {
              setCreateOpen(true);
            }}
            onShowShortcuts={() => {
              setShortcutsOpen(true);
            }}
          />
          <CreateIssueDialog
            org={org}
            open={createOpen}
            defaultProject={key}
            onClose={() => {
              setCreateOpen(false);
            }}
          />
        </>
      )}
    </div>
  );
}

function OrgRealtime({ org }: { org: string }) {
  useRealtime(org);
  return null;
}

function useOnline(): boolean {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const update = () => {
      setOnline(navigator.onLine);
    };
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    };
  }, []);
  return online;
}

/** Tells server administrators that outgoing email isn't configured yet (email pending). */
function EmailPendingNotice() {
  const serverAdmin = useIsServerAdmin();
  const config = useQuery(publicConfigQueryOptions);
  if (!serverAdmin || config.data?.emailEnabled !== false) {
    return null;
  }
  return (
    <div className={styles.offline} role="status">
      <MailWarning size={16} aria-hidden="true" />
      <span>
        Email isn’t set up: invitations, password resets and notifications are waiting in the queue.{' '}
        <Link to="/account/server/email">Set up email</Link>
      </span>
    </div>
  );
}
