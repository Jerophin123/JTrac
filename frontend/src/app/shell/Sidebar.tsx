import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from '@tanstack/react-router';
import {
  Bell,
  Building2,
  ChevronsUpDown,
  FolderKanban,
  House,
  LayoutDashboard,
  ListTodo,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Search,
  Settings,
  UserRound,
  X,
} from 'lucide-react';
import { orgQuery, orgsQuery, projectsQuery, unreadQuery } from '../../api/queries';
import { formatOrgRoleDescription } from '../../lib/roles';
import { ariaKeyShortcuts, formatBindingText, useShortcutSettings } from '../../lib/shortcuts';
import { logout, useCanCreateOrganizations, type Session } from '../../features/auth/session';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { Button } from '../../ui/Button';
import { Logo, LogoMark } from '../../ui/Logo';
import { Menu } from '../../ui/Menu';
import { OrgMark } from '../../ui/OrgMark';
import styles from './Sidebar.module.css';
import { SidebarSearch } from './SidebarSearch';

interface SidebarProps {
  id: string;
  session: Session;
  open: boolean;
  /** Collapsed to the icon rail (wide screens only). */
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onClose: () => void;
  onSignedOut: () => Promise<void>;
  /** Opens the command palette (the collapsed rail's search button). */
  onOpenSearch: () => void;
}

export function Sidebar({
  id,
  session,
  open,
  collapsed,
  onToggleCollapsed,
  onClose,
  onSignedOut,
  onOpenSearch,
}: SidebarProps) {
  const { activeBindingFor } = useShortcutSettings();
  const toggleKeys = activeBindingFor('toggleSidebar');
  const paletteKeys = activeBindingFor('commandPalette');
  const signOut = useMutation({ mutationFn: logout, onSuccess: onSignedOut });
  const { org } = useParams({ strict: false });
  const navigate = useNavigate();
  const { data: orgDetail } = useQuery({
    ...orgQuery(org ?? ''),
    enabled: Boolean(org),
  });
  const { data: orgs } = useQuery(orgsQuery);
  const activeFromList = orgs?.find((o) => o.slug === org);
  const activeLogo = orgDetail?.settings.logoUrl ?? activeFromList?.logoUrl;
  const activeColor = orgDetail?.settings.color ?? activeFromList?.color;
  const activeName = orgDetail?.name ?? activeFromList?.name;
  const activeRole = orgDetail?.role ?? activeFromList?.role;
  const active = activeFromList
    ? {
        ...activeFromList,
        name: activeName ?? activeFromList.name,
        color: activeColor ?? activeFromList.color,
        logoUrl: activeLogo ?? activeFromList.logoUrl,
        role: activeRole ?? activeFromList.role,
      }
    : undefined;

  return (
    <aside
      id={id}
      className={styles.sidebar}
      data-open={open || undefined}
      data-collapsed={collapsed || undefined}
      aria-label="Sidebar"
    >
      <div className={styles.brandRow}>
        <Link to="/" className={styles.brandLink} onClick={onClose} aria-label="JTRAC">
          <Logo height={26} className={styles.brandFull} aria-hidden="true" />
          <LogoMark size={26} className={styles.brandMark} aria-hidden="true" />
        </Link>
        <button
          type="button"
          className={styles.collapseButton}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-expanded={!collapsed}
          aria-controls={id}
          title={`${collapsed ? 'Expand' : 'Collapse'} sidebar${toggleKeys ? ` (${formatBindingText(toggleKeys)})` : ''}`}
          aria-keyshortcuts={ariaKeyShortcuts(toggleKeys)}
          onClick={onToggleCollapsed}
        >
          {collapsed ? <PanelLeftOpen size={16} aria-hidden="true" /> : <PanelLeftClose size={16} aria-hidden="true" />}
        </button>
        <Button
          variant="ghost"
          size="sm"
          className={styles.closeButton}
          icon={<X size={16} aria-hidden="true" />}
          aria-label="Close navigation"
          onClick={onClose}
        />
      </div>

      <OrgSwitcher current={org} collapsed={collapsed} onNavigate={onClose} />

      {org && (
        <>
          <div className={styles.searchSlot}>
            <SidebarSearch org={org} onNavigate={onClose} />
          </div>
          <button
            type="button"
            className={styles.searchButton}
            onClick={onOpenSearch}
            title={`Search${paletteKeys ? ` (${formatBindingText(paletteKeys)})` : ''}`}
            aria-keyshortcuts={ariaKeyShortcuts(paletteKeys)}
          >
            <Search size={15} aria-hidden="true" />
            <span className={styles.label}>Search</span>
          </button>
          <OrgNav org={org} collapsed={collapsed} onNavigate={onClose} />
          <ProjectList org={org} onNavigate={onClose} />
        </>
      )}

      <div className={styles.footer}>
        {signOut.isError && <Alert tone="danger">Sign-out failed. Try again.</Alert>}
        <Menu
          align="start"
          block
          side="top"
          items={[
            ...(active
              ? [
                  {
                    label: active.name,
                    description: formatOrgRoleDescription(active.role),
                    disabled: true,
                    onSelect: () => undefined,
                  },
                ]
              : []),
            { label: 'Profile & settings', icon: <UserRound size={15} aria-hidden="true" />, onSelect: () => void navigate({ to: '/account' }).then(onClose) },
            { label: 'Sign out', icon: <LogOut size={15} aria-hidden="true" />, onSelect: () => { signOut.mutate(); } },
          ]}
          trigger={(props) => (
            <button type="button" className={styles.user} title={collapsed ? session.user.displayName : undefined} {...props}>
              <Avatar name={session.user.displayName} />
              <span className={styles.userText}>
                <span className={styles.userName}>{session.user.displayName}</span>
                <span className={styles.userEmail}>@{session.user.handle}</span>
                {active && <span className={styles.userRole}>{formatOrgRoleDescription(active.role)}</span>}
              </span>
              <ChevronsUpDown size={14} aria-hidden="true" className={styles.chevron} />
            </button>
          )}
        />
      </div>
    </aside>
  );
}

function OrgIcon({
  name,
  logoUrl,
  color,
  size = 'md',
}: {
  name: string;
  logoUrl?: string | null;
  color?: string | null;
  size?: 'md' | 'sm';
}) {
  if (size === 'sm' && !logoUrl && !color) {
    return <Building2 size={15} aria-hidden="true" />;
  }
  return <OrgMark name={name} logoUrl={logoUrl} color={color} size={size === 'sm' ? 'xs' : 'sm'} />;
}

function OrgSwitcher({ current, collapsed, onNavigate }: { current: string | undefined; collapsed: boolean; onNavigate: () => void }) {
  const { data: orgs } = useQuery(orgsQuery);
  const { data: orgDetail } = useQuery({
    ...orgQuery(current ?? ''),
    enabled: Boolean(current),
  });
  const navigate = useNavigate();
  const canCreateOrgs = useCanCreateOrganizations();
  const fromList = orgs?.find((o) => o.slug === current);
  const active = current
    ? {
        name: orgDetail?.name ?? fromList?.name ?? current,
        role: orgDetail?.role ?? fromList?.role,
        color: orgDetail?.settings.color ?? fromList?.color,
        logoUrl: orgDetail?.settings.logoUrl ?? fromList?.logoUrl,
      }
    : undefined;
  return (
    <Menu
      align="start"
      block
      items={[
        ...(orgs ?? []).map((o) => ({
          label: o.name,
          description: formatOrgRoleDescription(o.role),
          selected: o.slug === current,
          icon: <OrgIcon name={o.name} logoUrl={o.logoUrl} color={o.color} size="sm" />,
          onSelect: () => void navigate({ to: '/$org', params: { org: o.slug } }).then(onNavigate),
        })),
        ...(canCreateOrgs
          ? [{ label: 'New organization', icon: <Plus size={15} aria-hidden="true" />, onSelect: () => void navigate({ to: '/new' }).then(onNavigate) }]
          : []),
      ]}
      trigger={(props) => (
        <button type="button" className={styles.orgButton} title={collapsed ? (active?.name ?? 'Choose organization') : undefined} {...props}>
          <OrgIcon name={active?.name ?? '?'} logoUrl={active?.logoUrl} color={active?.color} size="md" />
          <span className={styles.orgText}>
            <span className={styles.orgName}>{active?.name ?? 'Choose organization'}</span>
            {active && <span className={styles.orgRole}>{formatOrgRoleDescription(active.role)}</span>}
          </span>
          <ChevronsUpDown size={14} aria-hidden="true" className={styles.chevron} />
        </button>
      )}
    />
  );
}

function OrgNav({ org, collapsed, onNavigate }: { org: string; collapsed: boolean; onNavigate: () => void }) {
  const unread = useQuery(unreadQuery(org)).data?.count ?? 0;
  const items = [
    { to: '/$org', label: 'My work', icon: House, exact: true },
    { to: '/$org/inbox', label: 'Inbox', icon: Bell, exact: false },
    { to: '/$org/search', label: 'Issues', icon: ListTodo, exact: false },
    { to: '/$org/dashboards', label: 'Dashboards', icon: LayoutDashboard, exact: false },
    { to: '/$org/projects', label: 'Projects', icon: FolderKanban, exact: true },
    { to: '/$org/settings', label: 'Organization settings', icon: Settings, exact: false },
  ] as const;
  return (
    <nav aria-label="Primary" className={styles.nav}>
      <ul className={styles.navList}>
        {items.map(({ to, label, icon: Icon, exact }) => {
          const count = to === '/$org/inbox' && unread > 0 ? unread : 0;
          return (
            <li key={to}>
              <Link
                to={to}
                params={{ org }}
                className={styles.navItem}
                activeOptions={{ exact }}
                title={collapsed ? (count > 0 ? `${label} (${String(count)} unread)` : label) : undefined}
                onClick={onNavigate}
              >
                <Icon size={16} aria-hidden="true" />
                <span className={styles.label}>{label}</span>
                {/* Keeps the accessible name "Inbox 3 unread" rather than "Inbox3 unread"; flex ignores it visually. */}
                {count > 0 && ' '}
                {count > 0 && (
                  <span className={styles.count} aria-label={`${String(count)} unread`}>
                    {count > 99 ? '99+' : count}
                  </span>
                )}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

function ProjectList({ org, onNavigate }: { org: string; onNavigate: () => void }) {
  const { data: projects, isPending, isError } = useQuery(projectsQuery(org));
  const active = projects?.filter((p) => !p.archived) ?? [];
  return (
    <section className={styles.section} aria-labelledby="sidebar-projects">
      <h2 id="sidebar-projects" className={styles.sectionLabel}>
        Projects
      </h2>
      {isPending && <p className={styles.sectionEmpty}>Loading…</p>}
      {isError && <p className={styles.sectionEmpty}>Projects could not be loaded.</p>}
      {projects && active.length === 0 && <p className={styles.sectionEmpty}>No projects yet</p>}
      <ul className={styles.navList}>
        {active.map((project) => (
          <li key={project.id}>
            <Link to="/$org/p/$key" params={{ org, key: project.key }} className={styles.navItem} onClick={onNavigate}>
              <span className={styles.projectKey}>{project.key}</span>
              <span className={styles.projectName}>{project.name}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
