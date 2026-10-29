import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import {
  Bell,
  Bot,
  Building2,
  MailPlus,
  Pipette,
  Plus,
  RotateCw,
  ScrollText,
  Search,
  Trash2,
  Upload,
  UserMinus,
  Users,
  UsersRound,
  Webhook,
  X,
} from 'lucide-react';
import { useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, membersQuery, teamsQuery } from '../../api/queries';
import type { NotificationPreference, OrgRole, OrgSummary } from '../../api/types';
import { useOrg, useOrgSlug } from '../../app/params';
import { extractColorFromImage } from '../../lib/colorExtractor';
import { contrastRatio, LIGHT_INK, readableTextOn, relativeLuminance } from '../../lib/contrast';
import { describeError, fieldErrors } from '../../lib/errors';
import { formatDateTime, timeAgo } from '../../lib/format';
import { formatOrgRole } from '../../lib/roles';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Input, Select, TextArea } from '../../ui/Field';
import { OrgMark } from '../../ui/OrgMark';
import { Skeleton } from '../../ui/Page';
import {
  SaveBar,
  SettingRow,
  SettingsCard,
  SettingsEmpty,
  SettingsLayout,
  SettingsList,
  SettingsListItem,
  SettingsSection,
} from '../../ui/Settings';
import { Switch } from '../../ui/Switch';
import { Segmented } from '../../ui/Tabs';
import { useToast } from '../../ui/Toast';
import { sessionQueryOptions } from '../auth/session';
import { AgentConnectionsContent } from '../agents/AgentConnectionsPage';
import styles from './OrgSettings.module.css';

const PRESET_COLORS = ['#b4532a', '#2563eb', '#0d9488', '#16a34a', '#7c3aed', '#ea580c', '#e11d48', '#334155'];
const DEFAULT_COLOR = '#b4532a';

const ROLE_LABELS: Record<OrgRole, string> = { OWNER: 'Owner', ADMIN: 'Admin', MEMBER: 'Member', GUEST: 'Guest' };

function roleTone(role: string): 'accent' | 'neutral' {
  return role === 'OWNER' || role === 'ADMIN' ? 'accent' : 'neutral';
}

export function OrgSettingsLayout({ children }: { children: ReactNode }) {
  const org = useOrgSlug();
  const detail = useOrg();
  const admin = detail.role === 'OWNER' || detail.role === 'ADMIN';
  return (
    <SettingsLayout
      title="Settings"
      navLabel="Organization settings"
      context={
        <>
          <OrgMark name={detail.name} logoUrl={detail.settings.logoUrl} color={detail.settings.color} size="sm" />
          <strong className={styles.contextName}>{detail.name}</strong>
          <Badge tone={roleTone(detail.role)}>{formatOrgRole(detail.role)}</Badge>
        </>
      }
      groups={[
        {
          label: 'Organization',
          items: [
            { label: 'General', icon: <Building2 size={16} />, link: { to: '/$org/settings', params: { org } }, exact: true },
            { label: 'Members', icon: <Users size={16} />, link: { to: '/$org/settings/members', params: { org } } },
            { label: 'Teams', icon: <UsersRound size={16} />, link: { to: '/$org/settings/teams', params: { org } } },
          ],
        },
        {
          label: 'Personal',
          items: [{ label: 'My notifications', icon: <Bell size={16} />, link: { to: '/$org/settings/notifications', params: { org } } }],
        },
        {
          label: 'Developers',
          items: [
            { label: 'AI agents (MCP)', icon: <Bot size={16} />, link: { to: '/$org/settings/agents', params: { org } } },
            ...(admin
              ? [
                  { label: 'Webhooks', icon: <Webhook size={16} />, link: { to: '/$org/settings/webhooks' as const, params: { org } } },
                  { label: 'Audit log', icon: <ScrollText size={16} />, link: { to: '/$org/settings/audit' as const, params: { org } } },
                ]
              : []),
          ],
        },
      ]}
    >
      {children}
    </SettingsLayout>
  );
}

/** Shows how the chosen brand color renders, with the text color picked for contrast. */
function BrandPreview({ color }: { color: string }) {
  const ink = readableTextOn(color);
  if (!ink) {
    return null;
  }
  const ratio = contrastRatio(color, ink) ?? 0;
  const swatch = { '--preview-color': color, color: ink } as CSSProperties;
  return (
    <div className={styles.preview} role="group" aria-label="Brand color preview">
      <span className={styles.previewLabel}>Preview</span>
      <div className={styles.previewSample}>
        <span className={styles.previewButton} style={swatch}>
          Primary action
        </span>
        <span className={styles.previewChip} style={swatch}>
          3 unread
        </span>
      </div>
      <span className={styles.previewNote}>
        {ink === LIGHT_INK ? 'White' : 'Black'} text, {ratio.toFixed(1)}:1 contrast. Text and links adapt to light and dark themes automatically.
      </span>
    </div>
  );
}

/** A short, human description of the logo value (never the raw data URI). */
function describeLogo(url: string): { title: string; detail: string } {
  if (!url) {
    return { title: 'No logo yet', detail: 'The first letter of the name is shown instead.' };
  }
  if (url.startsWith('data:')) {
    const comma = url.indexOf(',');
    const header = url.slice(5, comma === -1 ? undefined : comma);
    const mime = header.split(';')[0] ?? '';
    const kind = (mime.split('/')[1] ?? 'image').replace('svg+xml', 'svg').toUpperCase();
    const payload = comma === -1 ? '' : url.slice(comma + 1);
    const bytes = header.includes(';base64') ? Math.floor((payload.length * 3) / 4) : payload.length;
    return { title: `Uploaded ${kind} image`, detail: bytes < 1024 ? `${String(bytes)} bytes` : `${(bytes / 1024).toFixed(1)} KB` };
  }
  try {
    const parsed = new URL(url);
    return { title: parsed.hostname, detail: parsed.pathname.split('/').filter(Boolean).pop() ?? url };
  } catch {
    return { title: 'Image URL', detail: url };
  }
}

export function OrgGeneralSettings() {
  const slug = useOrgSlug();
  const org = useOrg();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const admin = org.role === 'OWNER' || org.role === 'ADMIN';
  const isOwner = org.role === 'OWNER';
  const initial = {
    name: org.name,
    slug: org.slug,
    domains: org.settings.allowedEmailDomains.join(', '),
    visibility: org.settings.defaultProjectVisibility,
    membersCreate: org.settings.membersCanCreateProjects,
    color: org.settings.color ?? DEFAULT_COLOR,
    logoUrl: org.settings.logoUrl ?? '',
  };
  const [name, setName] = useState(initial.name);
  const [newSlug, setNewSlug] = useState(initial.slug);
  const [domains, setDomains] = useState(initial.domains);
  const [visibility, setVisibility] = useState(initial.visibility);
  const [membersCreate, setMembersCreate] = useState(initial.membersCreate);
  const [color, setColor] = useState(initial.color);
  const [logoUrl, setLogoUrl] = useState(initial.logoUrl);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [confirm, setConfirm] = useState(false);
  const [confirmName, setConfirmName] = useState('');

  const dirty =
    name !== initial.name ||
    newSlug !== initial.slug ||
    domains !== initial.domains ||
    visibility !== initial.visibility ||
    membersCreate !== initial.membersCreate ||
    color !== initial.color ||
    logoUrl !== initial.logoUrl;

  const discard = () => {
    setName(initial.name);
    setNewSlug(initial.slug);
    setDomains(initial.domains);
    setVisibility(initial.visibility);
    setMembersCreate(initial.membersCreate);
    setColor(initial.color);
    setLogoUrl(initial.logoUrl);
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 350 * 1024) {
      toast.error('Logo image must be smaller than 350 KB.');
      return;
    }
    const reader = new FileReader();
    reader.onload = async () => {
      if (typeof reader.result === 'string') {
        const dataUri = reader.result;
        setLogoUrl(dataUri);
        const extracted = await extractColorFromImage(dataUri);
        if (extracted) {
          setColor(extracted);
          toast.success(`Logo uploaded and extracted brand color ${extracted}`);
        }
      }
    };
    reader.readAsDataURL(file);
  };

  const save = useMutation({
    mutationFn: () =>
      call(() =>
        api.PATCH('/api/v1/orgs/{org}', {
          params: { path: { org: slug } },
          body: {
            name: name.trim(),
            slug: newSlug,
            version: org.version,
            settings: {
              allowedEmailDomains: domains.split(/[\s,]+/).map((d) => d.trim().toLowerCase()).filter(Boolean),
              defaultProjectVisibility: visibility,
              membersCanCreateProjects: membersCreate,
              color: isOwner ? (color.trim() || null) : (org.settings.color ?? null),
              logoUrl: isOwner ? (logoUrl.trim() || null) : (org.settings.logoUrl ?? null),
            },
          },
        }),
      ),
    onSuccess: async (saved) => {
      toast.success('Organization saved');
      queryClient.setQueryData(keys.org(saved.slug), saved);
      queryClient.setQueryData<OrgSummary[]>(keys.orgs, (old) =>
        old?.map((item) =>
          item.slug === slug
            ? {
                ...item,
                slug: saved.slug,
                name: saved.name,
                color: saved.settings.color ?? null,
                logoUrl: saved.settings.logoUrl ?? null,
              }
            : item,
        ),
      );
      await queryClient.invalidateQueries({ queryKey: keys.orgs });
      await queryClient.invalidateQueries({ queryKey: keys.org(saved.slug) });
      if (saved.slug !== slug) {
        await navigate({ to: '/$org/settings', params: { org: saved.slug }, replace: true });
      }
    },
  });
  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}', { params: { path: { org: slug } }, body: { confirmName } })),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: keys.org(slug) });
      await queryClient.invalidateQueries({ queryKey: keys.orgs });
      await navigate({ to: '/' });
    },
  });
  const errors = fieldErrors(save.error);
  const validColor = relativeLuminance(color) === null ? null : color;
  const colorError = errors['settings.color'] ?? (color && !validColor ? 'Use the #rrggbb format, for example #2f5bff.' : undefined);
  const uploaded = logoUrl.startsWith('data:');
  const logoSummary = describeLogo(logoUrl);

  return (
    <>
      <form
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {!admin && <Alert tone="info">Only owners and admins can change these settings.</Alert>}
        {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}

        <SettingsSection title="Organization profile" description="The name people see everywhere, and the address of every link to this organization.">
          <SettingsCard>
            <fieldset className="field-group" disabled={!admin}>
              <legend className="visually-hidden">Organization profile</legend>
              <div className="form-grid">
                <Input label="Name" maxLength={80} value={name} error={errors.name} onChange={(e) => { setName(e.target.value); }} />
                <Input
                  label="URL"
                  maxLength={40}
                  value={newSlug}
                  error={errors.slug}
                  hint="Changing it breaks old links to this organization."
                  onChange={(e) => { setNewSlug(e.target.value.toLowerCase()); }}
                />
              </div>
            </fieldset>
          </SettingsCard>
        </SettingsSection>

        <SettingsSection
          title="Branding"
          description="The logo and brand color everyone in this organization sees. Text colors adapt automatically for contrast in light and dark themes."
        >
          <SettingsCard>
            {!isOwner && <Alert tone="info">Only organization owners can change the brand color and logo.</Alert>}
            <fieldset className="field-group" disabled={!isOwner}>
              <legend className="visually-hidden">Branding</legend>
              <div className={styles.brandField}>
                <div className={styles.brandFieldHead}>
                  <span className={styles.fieldLabel}>Logo</span>
                  <span className={styles.note}>Square images work best. PNG, JPG, SVG, WebP or GIF, smaller than 350 KB.</span>
                </div>
                <div className={styles.logoField}>
                  <span className={styles.logoTile}>
                    <OrgMark name={name || org.name} logoUrl={logoUrl || null} color={validColor} size="lg" alt={logoUrl ? `${name || org.name} logo` : ''} />
                  </span>
                  <div className={styles.logoInfo}>
                    <span className={styles.logoTitle}>{logoSummary.title}</span>
                    <span className={styles.note}>{logoSummary.detail}</span>
                    <div className={styles.brandingActions}>
                      <Button
                        type="button"
                        size="sm"
                        disabled={!isOwner}
                        icon={<Upload size={14} aria-hidden="true" />}
                        onClick={() => { fileInputRef.current?.click(); }}
                      >
                        {logoUrl ? 'Replace' : 'Upload'}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        disabled={!isOwner || !logoUrl}
                        icon={<Pipette size={14} aria-hidden="true" />}
                        onClick={() => {
                          if (!logoUrl) return;
                          void (async () => {
                            const extracted = await extractColorFromImage(logoUrl);
                            if (extracted) {
                              setColor(extracted);
                              toast.success(`Extracted brand color: ${extracted}`);
                            } else {
                              toast.error('Could not extract a distinct color from this logo.');
                            }
                          })();
                        }}
                      >
                        Use its color
                      </Button>
                      {logoUrl && (
                        <Button type="button" size="sm" variant="ghost" disabled={!isOwner} icon={<X size={14} aria-hidden="true" />} onClick={() => { setLogoUrl(''); }}>
                          Remove
                        </Button>
                      )}
                      <input
                        ref={fileInputRef}
                        type="file"
                        accept="image/png,image/jpeg,image/svg+xml,image/webp,image/gif"
                        hidden
                        onChange={handleFileUpload}
                      />
                    </div>
                  </div>
                </div>
                <Input
                  label="Or use an image URL"
                  type="url"
                  value={uploaded ? '' : logoUrl}
                  disabled={!isOwner}
                  placeholder={uploaded ? 'https:// (replaces the upload)' : 'https://example.com/logo.svg'}
                  error={errors['settings.logoUrl']}
                  onChange={(e) => { setLogoUrl(e.target.value); }}
                />
              </div>

              <div className={styles.brandField}>
                <div className={styles.brandFieldHead}>
                  <span className={styles.fieldLabel} id="org-brand-color-label">
                    Brand color
                  </span>
                  <span className={styles.note}>Pick a preset, choose any color, or type a hex value.</span>
                </div>
                <div className={styles.colorRow}>
                  <div className={styles.colorField} data-invalid={colorError ? true : undefined}>
                    <label className={styles.colorSwatch} style={validColor ? { background: validColor } : undefined} title="Choose any color">
                      <input
                        type="color"
                        className={styles.colorNative}
                        aria-label="Choose any brand color"
                        value={validColor && color.length === 7 ? color : DEFAULT_COLOR}
                        disabled={!isOwner}
                        onChange={(e) => { setColor(e.target.value); }}
                      />
                    </label>
                    <input
                      className={styles.hexInput}
                      aria-labelledby="org-brand-color-label"
                      aria-invalid={colorError ? true : undefined}
                      aria-describedby={colorError ? 'org-brand-color-error' : undefined}
                      value={color}
                      disabled={!isOwner}
                      placeholder={DEFAULT_COLOR}
                      maxLength={7}
                      spellCheck={false}
                      autoComplete="off"
                      onChange={(e) => { setColor(e.target.value.trim()); }}
                    />
                  </div>
                  <div className={styles.swatches} role="group" aria-label="Color presets">
                    {PRESET_COLORS.map((preset) => (
                      <button
                        key={preset}
                        type="button"
                        className={styles.swatchButton}
                        style={{ backgroundColor: preset }}
                        data-active={color.toLowerCase() === preset.toLowerCase() ? true : undefined}
                        aria-pressed={color.toLowerCase() === preset.toLowerCase()}
                        aria-label={`Use ${preset}`}
                        disabled={!isOwner}
                        title={preset}
                        onClick={() => { setColor(preset); }}
                      />
                    ))}
                  </div>
                </div>
                {colorError && (
                  <p id="org-brand-color-error" className={styles.colorError}>
                    {colorError}
                  </p>
                )}
              </div>
              <BrandPreview color={color} />
            </fieldset>
          </SettingsCard>
        </SettingsSection>

        <SettingsSection title="Access and defaults" description="Who can be invited, and how new projects start out.">
          <SettingsCard>
            <fieldset className="field-group" disabled={!admin}>
              <legend className="visually-hidden">Access and defaults</legend>
              <SettingRow
                label="Allowed email domains"
                description="Comma-separated, for example example.com. Leave empty to allow any address."
              >
                <TextArea
                  label="Allowed email domains for invitations"
                  hideLabel
                  className={styles.domains}
                  rows={2}
                  value={domains}
                  error={errors['settings.allowedEmailDomains']}
                  onChange={(e) => { setDomains(e.target.value); }}
                />
              </SettingRow>
              <SettingRow
                label="Default visibility of new projects"
                description={visibility === 'PRIVATE' ? 'Only people and teams added to a project can see it.' : 'Every member can see new projects.'}
              >
                <Segmented
                  label="Default visibility of new projects"
                  value={visibility}
                  options={[
                    { value: 'ORGANIZATION', label: 'Organization' },
                    { value: 'PRIVATE', label: 'Private' },
                  ]}
                  onChange={(next) => { setVisibility(next); }}
                />
              </SettingRow>
              <SettingRow label="Members can create projects" description="When off, only owners and admins create projects.">
                <Switch label="Members can create projects" hideLabel checked={membersCreate} onChange={(e) => { setMembersCreate(e.target.checked); }} />
              </SettingRow>
            </fieldset>
          </SettingsCard>
        </SettingsSection>

        {admin && <SaveBar dirty={dirty} saving={save.isPending} onDiscard={discard} />}
      </form>

      {isOwner && (
        <SettingsSection title="Danger zone" tone="danger" description="Irreversible actions. Take care.">
          <SettingsCard>
            <SettingRow label="Delete this organization" description="Removes access for everyone immediately and revokes pending invitations.">
              <Button variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirm(true); }}>
                Delete organization…
              </Button>
            </SettingRow>
          </SettingsCard>
          <Dialog
            open={confirm}
            size="sm"
            title={'Delete ' + org.name + '?'}
            onClose={() => { setConfirm(false); }}
            footer={
              <>
                <Button onClick={() => { setConfirm(false); }}>Cancel</Button>
                <Button variant="danger" loading={remove.isPending} disabled={confirmName.trim() !== org.name} onClick={() => { remove.mutate(); }}>
                  Delete permanently
                </Button>
              </>
            }
          >
            <div className="stack">
              {remove.isError && <Alert tone="danger">{describeError(remove.error)}</Alert>}
              <p>
                This action <strong>cannot be undone</strong>. All issues, projects, cycles, and data belonging to{' '}
                <strong>{org.name}</strong> will be permanently deleted.
              </p>
              <Input label={'Type "' + org.name + '" to confirm'} value={confirmName} autoComplete="off" onChange={(e) => { setConfirmName(e.target.value); }} />
            </div>
          </Dialog>
        </SettingsSection>
      )}
    </>
  );
}

export function MembersSettings() {
  const slug = useOrgSlug();
  const org = useOrg();
  const queryClient = useQueryClient();
  const toast = useToast();
  const session = useQuery(sessionQueryOptions);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const debounced = useDebouncedValue(q, 250);
  const members = useQuery(membersQuery(slug, debounced, page, 50));
  const admin = org.role === 'OWNER' || org.role === 'ADMIN';
  const invitations = useQuery({
    queryKey: keys.invitations(slug),
    enabled: admin,
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/invitations', { params: { path: { org: slug } } })),
  });
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<OrgRole>('MEMBER');
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['org', slug, 'members'] });
    await queryClient.invalidateQueries({ queryKey: keys.invitations(slug) });
  };
  const onError = (e: unknown) => { toast.error(describeError(e)); };
  const changeRole = useMutation({
    mutationFn: ({ userId, role: next }: { userId: string; role: OrgRole }) =>
      call(() => api.PATCH('/api/v1/orgs/{org}/members/{userId}', { params: { path: { org: slug, userId } }, body: { role: next } })),
    onSuccess: refresh,
    onError,
  });
  const removeMember = useMutation({
    mutationFn: (userId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/members/{userId}', { params: { path: { org: slug, userId } } })),
    onSuccess: refresh,
    onError,
  });
  const invite = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/invitations', { params: { path: { org: slug } }, body: { email: email.trim(), role } })),
    onSuccess: async () => {
      toast.success(`Invitation sent to ${email.trim()}`);
      setEmail('');
      await refresh();
    },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => call(() => api.DELETE('/api/v1/orgs/{org}/invitations/{id}', { params: { path: { org: slug, id } } })),
    onSuccess: refresh,
    onError,
  });
  const resend = useMutation({
    mutationFn: (id: string) => call(() => api.POST('/api/v1/orgs/{org}/invitations/{id}/resend', { params: { path: { org: slug, id } } })),
    onSuccess: () => { toast.success('Invitation sent again'); },
    onError,
  });
  const inviteErrors = fieldErrors(invite.error);
  const assignable = org.role === 'OWNER' ? (['OWNER', 'ADMIN', 'MEMBER', 'GUEST'] as const) : (['ADMIN', 'MEMBER', 'GUEST'] as const);

  return (
    <>
      {admin && (
        <SettingsSection title="Invite people" description="Invitations expire after 7 days. Guests only see projects they are explicitly added to.">
          <SettingsCard>
            {invite.isError && Object.keys(inviteErrors).length === 0 && <Alert tone="danger">{describeError(invite.error)}</Alert>}
            <form className={styles.inlineForm} onSubmit={(e) => { e.preventDefault(); if (email.trim()) { invite.mutate(); } }}>
              <Input
                label="Email address"
                type="email"
                placeholder="name@example.com"
                value={email}
                error={inviteErrors.email}
                onChange={(e) => { setEmail(e.target.value); }}
                className={styles.grow}
              />
              <Select label="Role" value={role} onChange={(e) => { setRole(e.target.value as OrgRole); }}>
                {assignable.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </Select>
              <Button type="submit" variant="primary" icon={<MailPlus size={14} aria-hidden="true" />} loading={invite.isPending} className={styles.alignEnd}>
                Send invitation
              </Button>
            </form>
          </SettingsCard>
          {invitations.data && invitations.data.length > 0 && (
            <SettingsCard title="Pending invitations" meta={`${String(invitations.data.length)} waiting for a reply`} flush>
              <SettingsList label="Pending invitations">
                {invitations.data.map((inv) => (
                  <SettingsListItem
                    key={inv.id}
                    leading={<Avatar name={inv.email} />}
                    title={
                      <>
                        {inv.email} <Badge tone={roleTone(inv.role)}>{formatOrgRole(inv.role)}</Badge>
                      </>
                    }
                    meta={`Invited ${timeAgo(inv.createdAt)} · expires ${timeAgo(inv.expiresAt)}`}
                  >
                    <Button size="sm" variant="ghost" icon={<RotateCw size={14} aria-hidden="true" />} onClick={() => { resend.mutate(inv.id); }}>
                      Resend
                    </Button>
                    <Button size="sm" variant="ghost" icon={<X size={14} aria-hidden="true" />} onClick={() => { revoke.mutate(inv.id); }}>
                      Revoke
                    </Button>
                  </SettingsListItem>
                ))}
              </SettingsList>
            </SettingsCard>
          )}
        </SettingsSection>
      )}

      <SettingsSection
        title="Members"
        layout="stacked"
        description={members.data ? `${String(members.data.total)} ${members.data.total === 1 ? 'person has' : 'people have'} access to this organization.` : 'Everyone with access to this organization.'}
        actions={
          <div className={styles.search}>
            <Search size={15} aria-hidden="true" className={styles.searchIcon} />
            <Input label="Search members" hideLabel placeholder="Search by name, handle or email" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          </div>
        }
      >
        <SettingsCard
          flush
          footer={
            members.data && members.data.total > 50 ? (
              <>
                <span className={styles.footerNote}>
                  Page {page + 1} of {Math.ceil(members.data.total / 50)}
                </span>
                <Button size="sm" disabled={page === 0} onClick={() => { setPage(page - 1); }}>
                  Previous
                </Button>
                <Button size="sm" disabled={(page + 1) * 50 >= members.data.total} onClick={() => { setPage(page + 1); }}>
                  Next
                </Button>
              </>
            ) : undefined
          }
        >
          {members.isPending && (
            <div className={styles.pad}>
              <Skeleton lines={5} />
            </div>
          )}
          {members.isError && (
            <div className={styles.pad}>
              <Alert tone="danger">{describeError(members.error)}</Alert>
            </div>
          )}
          {members.data?.items.length === 0 && <SettingsEmpty>No members match “{q}”.</SettingsEmpty>}
          {members.data && members.data.items.length > 0 && (
            <SettingsList label="Members">
              {members.data.items.map((m) => {
                const self = m.userId === session.data?.user.id;
                const canChange = admin && (org.role === 'OWNER' || m.role !== 'OWNER');
                return (
                  <SettingsListItem
                    key={m.userId}
                    leading={<Avatar name={m.displayName} />}
                    title={
                      <>
                        {m.displayName}
                        {self && <Badge tone="info">You</Badge>}
                      </>
                    }
                    meta={
                      <>
                        @{m.handle} · {m.email}
                        {m.teams.length > 0 && ` · ${m.teams.join(', ')}`}
                      </>
                    }
                  >
                    {canChange ? (
                      <Select label={`Role of ${m.displayName}`} hideLabel value={m.role} onChange={(e) => { changeRole.mutate({ userId: m.userId, role: e.target.value as OrgRole }); }}>
                        {assignable.map((r) => (
                          <option key={r} value={r}>
                            {ROLE_LABELS[r]}
                          </option>
                        ))}
                      </Select>
                    ) : (
                      <Badge tone={roleTone(m.role)}>{formatOrgRole(m.role)}</Badge>
                    )}
                    {(canChange || self) && (
                      <Button size="sm" variant="ghost" icon={<UserMinus size={14} aria-hidden="true" />} onClick={() => { removeMember.mutate(m.userId); }}>
                        {self ? 'Leave' : 'Remove'}
                      </Button>
                    )}
                  </SettingsListItem>
                );
              })}
            </SettingsList>
          )}
        </SettingsCard>
      </SettingsSection>
    </>
  );
}

export function TeamsSettings() {
  const slug = useOrgSlug();
  const org = useOrg();
  const queryClient = useQueryClient();
  const toast = useToast();
  const teams = useQuery(teamsQuery(slug));
  const members = useQuery(membersQuery(slug, '', 0, 200));
  const admin = org.role === 'OWNER' || org.role === 'ADMIN';
  const [name, setName] = useState('');
  const [adding, setAdding] = useState<Record<string, string>>({});
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.teams(slug) });
  const onError = (e: unknown) => { toast.error(describeError(e)); };
  const create = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/teams', { params: { path: { org: slug } }, body: { name: name.trim() } })),
    onSuccess: async () => { setName(''); await refresh(); },
    onError,
  });
  const remove = useMutation({
    mutationFn: (teamId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/teams/{teamId}', { params: { path: { org: slug, teamId } } })),
    onSuccess: refresh,
    onError,
  });
  const addMember = useMutation({
    mutationFn: ({ teamId, userId }: { teamId: string; userId: string }) =>
      call(() => api.PUT('/api/v1/orgs/{org}/teams/{teamId}/members/{userId}', { params: { path: { org: slug, teamId, userId } } })),
    onSuccess: refresh,
    onError,
  });
  const removeMember = useMutation({
    mutationFn: ({ teamId, userId }: { teamId: string; userId: string }) =>
      call(() => api.DELETE('/api/v1/orgs/{org}/teams/{teamId}/members/{userId}', { params: { path: { org: slug, teamId, userId } } })),
    onSuccess: refresh,
    onError,
  });
  return (
    <SettingsSection
      title="Teams"
      layout="stacked"
      description="Grant a team a role in a project to give all of its members access at once."
      actions={
        admin && (
          <form className={styles.inlineForm} onSubmit={(e) => { e.preventDefault(); if (name.trim()) { create.mutate(); } }}>
            <Input label="New team" hideLabel placeholder="New team name" maxLength={60} value={name} onChange={(e) => { setName(e.target.value); }} />
            <Button type="submit" variant="primary" icon={<Plus size={14} aria-hidden="true" />} loading={create.isPending} disabled={!name.trim()}>
              Create team
            </Button>
          </form>
        )
      }
    >
      {teams.isPending && <Skeleton lines={4} />}
      {teams.isError && <Alert tone="danger">{describeError(teams.error)}</Alert>}
      {teams.data?.length === 0 && (
        <SettingsCard>
          <SettingsEmpty>No teams yet.{admin ? ' Create one to manage project access for a group.' : ''}</SettingsEmpty>
        </SettingsCard>
      )}
      {teams.data && teams.data.length > 0 && (
        <div className={styles.teamGrid}>
          {teams.data.map((team) => (
            <SettingsCard
              key={team.id}
              title={team.name}
              meta={`${String(team.members.length)} ${team.members.length === 1 ? 'member' : 'members'}`}
              actions={
                admin && (
                  <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Delete team ${team.name}`} onClick={() => { remove.mutate(team.id); }} />
                )
              }
              flush
              footer={
                admin ? (
                  <form
                    className={styles.teamAdd}
                    onSubmit={(e) => {
                      e.preventDefault();
                      const userId = adding[team.id];
                      if (userId) {
                        addMember.mutate({ teamId: team.id, userId }, { onSuccess: () => { setAdding({ ...adding, [team.id]: '' }); } });
                      }
                    }}
                  >
                    <Select label={`Add member to ${team.name}`} hideLabel className={styles.grow} value={adding[team.id] ?? ''} onChange={(e) => { setAdding({ ...adding, [team.id]: e.target.value }); }}>
                      <option value="">Add a member…</option>
                      {(members.data?.items ?? []).filter((m) => !team.members.some((t) => t.userId === m.userId)).map((m) => (
                        <option key={m.userId} value={m.userId}>
                          {m.displayName}
                        </option>
                      ))}
                    </Select>
                    <Button type="submit" size="sm" disabled={!adding[team.id]}>
                      Add
                    </Button>
                  </form>
                ) : undefined
              }
            >
              {team.members.length === 0 ? (
                <SettingsEmpty>No members yet.</SettingsEmpty>
              ) : (
                <SettingsList label={`Members of ${team.name}`}>
                  {team.members.map((m) => (
                    <SettingsListItem key={m.userId} leading={<Avatar name={m.displayName} />} title={m.displayName} meta={`@${m.handle}`}>
                      {admin && (
                        <Button
                          size="sm"
                          variant="ghost"
                          icon={<X size={14} aria-hidden="true" />}
                          aria-label={`Remove ${m.displayName} from ${team.name}`}
                          onClick={() => { removeMember.mutate({ teamId: team.id, userId: m.userId }); }}
                        />
                      )}
                    </SettingsListItem>
                  ))}
                </SettingsList>
              )}
            </SettingsCard>
          ))}
        </div>
      )}
    </SettingsSection>
  );
}

const PREF_LABELS: Record<NotificationPreference['kind'], string> = {
  ASSIGNED: 'An issue is assigned to me',
  MENTIONED: 'Someone mentions me',
  COMMENTED: 'New comments on issues I watch',
  STATUS_CHANGED: 'Status changes on issues I watch',
  UPDATED: 'Other changes on issues I watch',
};

export function NotificationSettings() {
  const slug = useOrgSlug();
  const queryClient = useQueryClient();
  const toast = useToast();
  const prefs = useQuery({
    queryKey: keys.preferences(slug),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/notifications/preferences', { params: { path: { org: slug } } })),
  });
  const save = useMutation({
    mutationFn: (body: NotificationPreference[]) => call(() => api.PUT('/api/v1/orgs/{org}/notifications/preferences', { params: { path: { org: slug } }, body })),
    onSuccess: (data) => {
      queryClient.setQueryData(keys.preferences(slug), data);
      toast.success('Preferences saved');
    },
    onError: (e) => { toast.error(describeError(e)); },
  });
  const update = (kind: NotificationPreference['kind'], patch: Partial<NotificationPreference>) => {
    if (prefs.data) {
      save.mutate(prefs.data.map((p) => (p.kind === kind ? { ...p, ...patch } : p)));
    }
  };
  return (
    <SettingsSection
      title="Notify me when…"
      description="Choose where each kind of update reaches you. You’re never notified about your own changes, or about fields your role can’t see. Changes save immediately."
    >
      {prefs.isPending && <Skeleton lines={5} />}
      {prefs.isError && <Alert tone="danger">{describeError(prefs.error)}</Alert>}
      {prefs.data && (
        <SettingsCard flush>
          <div className={styles.prefHead} aria-hidden="true">
            <span>Event</span>
            <span>Inbox</span>
            <span>Email</span>
          </div>
          <ul className={styles.prefList} aria-label="Notification preferences">
            {prefs.data.map((p) => (
              <li key={p.kind} className={styles.prefRow}>
                <span className={styles.prefLabel}>{PREF_LABELS[p.kind]}</span>
                <Switch label="Inbox" hideLabel aria-label={`${PREF_LABELS[p.kind]}: inbox`} checked={p.inApp} onChange={(e) => { update(p.kind, { inApp: e.target.checked }); }} />
                <Switch label="Email" hideLabel aria-label={`${PREF_LABELS[p.kind]}: email`} checked={p.email} onChange={(e) => { update(p.kind, { email: e.target.checked }); }} />
              </li>
            ))}
          </ul>
        </SettingsCard>
      )}
    </SettingsSection>
  );
}

export function AuditSettings() {
  const slug = useOrgSlug();
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const debounced = useDebouncedValue(action, 300);
  const audit = useQuery({
    queryKey: keys.audit(slug, debounced, page),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/audit', { params: { path: { org: slug }, query: { action: debounced || undefined, page, size: 50 } } })),
    placeholderData: keepPreviousData,
  });
  return (
    <SettingsSection
      title="Audit log"
      layout="stacked"
      description="Every administrative change in this organization, newest first."
      actions={
        <div className={styles.search}>
          <Search size={15} aria-hidden="true" className={styles.searchIcon} />
          <Input label="Filter by action" hideLabel placeholder="Action prefix, e.g. member. or webhook." value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} />
        </div>
      }
    >
      <SettingsCard
        flush
        footer={
          audit.data && audit.data.total > 50 ? (
            <>
              <span className={styles.footerNote}>{audit.data.total} events</span>
              <Button size="sm" disabled={page === 0} onClick={() => { setPage(page - 1); }}>
                Newer
              </Button>
              <Button size="sm" disabled={(page + 1) * 50 >= audit.data.total} onClick={() => { setPage(page + 1); }}>
                Older
              </Button>
            </>
          ) : undefined
        }
      >
        {audit.isError && (
          <div className={styles.pad}>
            <Alert tone="danger">{describeError(audit.error)}</Alert>
          </div>
        )}
        {audit.isPending && (
          <div className={styles.pad}>
            <Skeleton lines={6} />
          </div>
        )}
        {audit.data?.items.length === 0 && <SettingsEmpty>No events{debounced ? ` starting with “${debounced}”` : ''}.</SettingsEmpty>}
        {audit.data && audit.data.items.length > 0 && (
          <div className={styles.scroll}>
            <table className={styles.audit}>
              <caption className="visually-hidden">Audit events</caption>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Who</th>
                  <th scope="col">Action</th>
                  <th scope="col">Target</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {audit.data.items.map((e) => (
                  <tr key={e.id}>
                    <td className={styles.nowrap}>
                      <time dateTime={e.occurredAt} title={timeAgo(e.occurredAt)}>
                        {formatDateTime(e.occurredAt)}
                      </time>
                    </td>
                    <td>{e.actorName ?? 'System'}</td>
                    <td>
                      <code className={styles.action}>{e.action}</code>
                    </td>
                    <td>{e.targetType ? `${e.targetType} ${e.targetId ?? ''}` : ''}</td>
                    <td className={styles.details}>{Object.keys(e.details).length ? JSON.stringify(e.details) : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SettingsCard>
    </SettingsSection>
  );
}

export function AgentSettings() {
  return <AgentConnectionsContent />;
}
