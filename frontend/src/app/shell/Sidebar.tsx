import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from '@tanstack/react-router';
import {
  Bell,
  Building2,
  ChevronsUpDown,
  FolderKanban,
  House,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Palette,
  Plus,
  Search,
  Server,
  Settings,
  ShieldCheck,
  UserRound,
  X,
} from 'lucide-react';
import { orgQuery, orgsQuery, projectsQuery, unreadQuery } from '../../api/queries';
import { formatOrgRoleDescription } from '../../lib/roles';
import { logout, useCanCreateOrganizations, useIsServerAdmin, type Session } from '../../features/auth/session';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { Button } from '../../ui/Button';
import { Logo } from '../../ui/Logo';
import { Menu } from '../../ui/Menu';
import { OrgMark } from '../../ui/OrgMark';
import styles from './Sidebar.module.css';

interface SidebarProps {
  id: string;
  session: Session;
  open: boolean;
  onClose: () => void;
  onSignedOut: () => Promise<void>;
  onOpenSearch: () => void;
}

export function Sidebar({ id, session, open, onClose, onSignedOut, onOpenSearch }: SidebarProps) {
  const signOut = useMutation({ mutationFn: logout, onSuccess: onSignedOut });
  const serverAdmin = useIsServerAdmin();
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
    <aside id={id} className={styles.sidebar} data-open={open || undefined} aria-label="Sidebar">
      <div className={styles.brandRow}>
        <Link to="/" className={styles.brandLink} onClick={onClose} aria-label="JTRAC">
          <Logo height={26} />
        </Link>
        <Button
          variant="ghost"
          size="sm"
          className={styles.closeButton}
          icon={<X size={16} aria-hidden="true" />}
          aria-label="Close navigation"
          onClick={onClose}
        />
      </div>

      <OrgSwitcher current={org} onNavigate={onClose} />

      {org && (
        <>
          <button type="button" className={styles.searchButton} onClick={onOpenSearch}>
            <Search size={15} aria-hidden="true" />
            <span>Search</span>
            <kbd className={styles.kbd} aria-label="Control K">
              Ctrl K
            </kbd>
          </button>
          <OrgNav org={org} onNavigate={onClose} />
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
            { label: 'Profile', icon: <UserRound size={15} aria-hidden="true" />, onSelect: () => void navigate({ to: '/account' }).then(onClose) },
            {
              label: 'API tokens',
              icon: <KeyRound size={15} aria-hidden="true" />,
              onSelect: () => void navigate({ to: '/account/tokens' }).then(onClose),
            },
            ...(serverAdmin
              ? [
                  {
                    label: 'Server settings',
                    icon: <Server size={15} aria-hidden="true" />,
                    onSelect: () => void navigate({ to: '/server/settings' }).then(onClose),
                  },
                ]
              : []),
            {
              label: 'Privacy & data',
              icon: <ShieldCheck size={15} aria-hidden="true" />,
              onSelect: () => void navigate({ to: '/account/privacy' }).then(onClose),
            },
            {
              label: 'Appearance',
              icon: <Palette size={15} aria-hidden="true" />,
              onSelect: () => void navigate({ to: '/account/appearance' }).then(onClose),
            },
            { label: 'Sign out', icon: <LogOut size={15} aria-hidden="true" />, onSelect: () => { signOut.mutate(); } },
          ]}
          trigger={(props) => (
            <button type="button" className={styles.user} {...props}>
              <Avatar name={session.user.displayName} />
              <span className={styles.userText}>
                <span className={styles.userName}>{session.user.displayName}</span>
                <span className={styles.userEmail}>@{session.user.handle}</span>
                {active && <span className={styles.userRole}>{formatOrgRoleDescription(active.role)}</span>}
              </span>
              <ChevronsUpDown size={14} aria-hidden="true" />
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

function OrgSwitcher({ current, onNavigate }: { current: string | undefined; onNavigate: () => void }) {
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
        <button type="button" className={styles.orgButton} {...props}>
          <OrgIcon name={active?.name ?? '?'} logoUrl={active?.logoUrl} color={active?.color} size="md" />
          <span className={styles.orgText}>
            <span className={styles.orgName}>{active?.name ?? 'Choose organization'}</span>
            {active && <span className={styles.orgRole}>{formatOrgRoleDescription(active.role)}</span>}
          </span>
          <ChevronsUpDown size={14} aria-hidden="true" />
        </button>
      )}
    />
  );
}

function OrgNav({ org, onNavigate }: { org: string; onNavigate: () => void }) {
  const unread = useQuery(unreadQuery(org)).data?.count ?? 0;
  return (
    <nav aria-label="Primary" className={styles.nav}>
      <ul className={styles.navList}>
        <li>
          <Link to="/$org" params={{ org }} className={styles.navItem} activeOptions={{ exact: true }} onClick={onNavigate}>
            <House size={16} aria-hidden="true" />
            My work
          </Link>
        </li>
        <li>
          <Link to="/$org/inbox" params={{ org }} className={styles.navItem} onClick={onNavigate}>
            <Bell size={16} aria-hidden="true" />
            Inbox
            {unread > 0 && (
              <span className={styles.count} aria-label={`${String(unread)} unread`}>
                {unread > 99 ? '99+' : unread}
              </span>
            )}
          </Link>
        </li>
        <li>
          <Link to="/$org/search" params={{ org }} className={styles.navItem} onClick={onNavigate}>
            <Search size={16} aria-hidden="true" />
            Issues
          </Link>
        </li>
        <li>
          <Link to="/$org/dashboards" params={{ org }} className={styles.navItem} onClick={onNavigate}>
            <LayoutDashboard size={16} aria-hidden="true" />
            Dashboards
          </Link>
        </li>
        <li>
          <Link to="/$org/projects" params={{ org }} className={styles.navItem} activeOptions={{ exact: true }} onClick={onNavigate}>
            <FolderKanban size={16} aria-hidden="true" />
            Projects
          </Link>
        </li>
        <li>
          <Link to="/$org/settings" params={{ org }} className={styles.navItem} onClick={onNavigate}>
            <Settings size={16} aria-hidden="true" />
            Settings
          </Link>
        </li>
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
