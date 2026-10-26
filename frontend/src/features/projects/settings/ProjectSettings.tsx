import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import {
  Archive,
  ArchiveRestore,
  Check,
  Minus,
  Plus,
  Settings2,
  Shield,
  SlidersHorizontal,
  Tags,
  Trash2,
  Upload,
  UserPlus,
  UsersRound,
  Workflow as WorkflowIcon,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { call } from '../../../api/call';
import { api } from '../../../api/client';
import { accessQuery, catalogQuery, keys, membersQuery, teamsQuery } from '../../../api/queries';
import type { ProjectPermission, ProjectRole } from '../../../api/types';
import { useOrgSlug, useProject } from '../../../app/params';
import { describeError, fieldErrors } from '../../../lib/errors';
import { Alert } from '../../../ui/Alert';
import { Avatar } from '../../../ui/Avatar';
import { Badge } from '../../../ui/Badge';
import { Button } from '../../../ui/Button';
import { Dialog } from '../../../ui/Dialog';
import { Checkbox, Input, Select, TextArea } from '../../../ui/Field';
import { Skeleton } from '../../../ui/Page';
import {
  SaveBar,
  SettingRow,
  SettingsCard,
  SettingsEmpty,
  SettingsLayout,
  SettingsList,
  SettingsListItem,
  SettingsSection,
} from '../../../ui/Settings';
import { Segmented } from '../../../ui/Tabs';
import { useToast } from '../../../ui/Toast';
import styles from './ProjectSettings.module.css';

export function ProjectSettingsLayout({ children }: { children: React.ReactNode }) {
  const org = useOrgSlug();
  const project = useProject();
  const params = { org, key: project.key };
  return (
    <SettingsLayout
      navLabel="Project settings"
      groups={[
        {
          label: 'Project',
          items: [
            { label: 'General', icon: <Settings2 size={16} />, link: { to: '/$org/p/$key/settings', params }, exact: true },
            { label: 'Access & roles', icon: <Shield size={16} />, link: { to: '/$org/p/$key/settings/access', params } },
          ],
        },
        {
          label: 'Configuration',
          items: [
            { label: 'Workflow', icon: <WorkflowIcon size={16} />, link: { to: '/$org/p/$key/settings/workflow', params } },
            { label: 'Fields', icon: <SlidersHorizontal size={16} />, link: { to: '/$org/p/$key/settings/fields', params } },
            { label: 'Types, labels & milestones', icon: <Tags size={16} />, link: { to: '/$org/p/$key/settings/catalog', params } },
          ],
        },
        {
          label: 'Data',
          items: [{ label: 'Import', icon: <Upload size={16} />, link: { to: '/$org/p/$key/settings/import', params } }],
        },
      ]}
    >
      {children}
    </SettingsLayout>
  );
}

function useRefreshProject() {
  const queryClient = useQueryClient();
  const org = useOrgSlug();
  const project = useProject();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: keys.project(org, project.key) });
    await queryClient.invalidateQueries({ queryKey: keys.projects(org) });
  };
}

export function ProjectGeneralSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const catalog = useQuery(catalogQuery(org, project.key));
  const members = useQuery(membersQuery(org, '', 0, 200));
  const refresh = useRefreshProject();
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const initial = {
    name: project.name,
    description: project.description ?? '',
    visibility: project.visibility,
    leadId: project.leadId ?? '',
    defaultRoleId: project.defaultRoleId ?? '',
  };
  const [name, setName] = useState(initial.name);
  const [description, setDescription] = useState(initial.description);
  const [visibility, setVisibility] = useState(initial.visibility);
  const [leadId, setLeadId] = useState(initial.leadId);
  const [defaultRoleId, setDefaultRoleId] = useState(initial.defaultRoleId);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmKey, setConfirmKey] = useState('');
  const path = { params: { path: { org, key: project.key } } };

  const dirty =
    name !== initial.name ||
    description !== initial.description ||
    visibility !== initial.visibility ||
    leadId !== initial.leadId ||
    defaultRoleId !== initial.defaultRoleId;
  const discard = () => {
    setName(initial.name);
    setDescription(initial.description);
    setVisibility(initial.visibility);
    setLeadId(initial.leadId);
    setDefaultRoleId(initial.defaultRoleId);
  };

  const save = useMutation({
    mutationFn: () =>
      call(() =>
        api.PATCH('/api/v1/orgs/{org}/projects/{key}', {
          ...path,
          body: { name: name.trim(), description: description.trim() || null, visibility, leadId: leadId || null, defaultRoleId: defaultRoleId || null, version: project.version },
        }),
      ),
    onSuccess: async () => {
      toast.success('Project saved');
      await refresh();
    },
  });
  const archive = useMutation({
    mutationFn: (archived: boolean) =>
      call(() => (archived ? api.POST('/api/v1/orgs/{org}/projects/{key}/archive', path) : api.POST('/api/v1/orgs/{org}/projects/{key}/restore', path))),
    onSuccess: async (_d, archived) => {
      toast.success(archived ? 'Project archived' : 'Project restored');
      await refresh();
    },
    onError: (error) => { toast.error(describeError(error)); },
  });
  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}', { ...path, body: { confirmKey } })),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: keys.project(org, project.key) });
      await queryClient.invalidateQueries({ queryKey: keys.projects(org) });
      await navigate({ to: '/$org/projects', params: { org } });
    },
  });
  const errors = fieldErrors(save.error);

  return (
    <>
      <form
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}
        <SettingsSection title="Details" description="The project's name and what it is for. The key prefixes every issue, like KEY-42.">
          <SettingsCard>
            <div className="form-grid">
              <Input label="Name" maxLength={80} value={name} error={errors.name} onChange={(e) => { setName(e.target.value); }} />
              <Input label="Key" value={project.key} disabled hint="Keys are permanent so issue references never break." />
            </div>
            <TextArea label="Description" rows={3} value={description} onChange={(e) => { setDescription(e.target.value); }} />
          </SettingsCard>
        </SettingsSection>

        <SettingsSection title="Access defaults" description="Who sees this project, the role members get, and who leads it.">
          <SettingsCard>
            <SettingRow
              label="Visibility"
              description={visibility === 'PRIVATE' ? 'Only members and teams you add can see it.' : 'Every member (not guests) gets the default role.'}
            >
              <Segmented
                label="Visibility"
                value={visibility}
                options={[
                  { value: 'ORGANIZATION', label: 'Organization' },
                  { value: 'PRIVATE', label: 'Private' },
                ]}
                onChange={(next) => { setVisibility(next); }}
              />
            </SettingRow>
            <SettingRow label="Default role for organization members" description="Applies to members not listed under Access & roles.">
              <Select label="Default role for organization members" hideLabel value={defaultRoleId} error={errors.defaultRoleId} onChange={(e) => { setDefaultRoleId(e.target.value); }}>
                <option value="">No access by default</option>
                {(catalog.data?.roles ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            </SettingRow>
            <SettingRow label="Project lead" description="The person responsible for this project.">
              <Select label="Project lead" hideLabel value={leadId} error={errors.leadId} onChange={(e) => { setLeadId(e.target.value); }}>
                <option value="">No lead</option>
                {(members.data?.items ?? []).map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.displayName}
                  </option>
                ))}
              </Select>
            </SettingRow>
          </SettingsCard>
        </SettingsSection>

        <SaveBar dirty={dirty} saving={save.isPending} onDiscard={discard} />
      </form>

      <SettingsSection title="Lifecycle" description="Pause a project without losing anything.">
        <SettingsCard>
          <SettingRow
            label={project.archived ? 'This project is archived' : 'Archive this project'}
            description={
              project.archived
                ? 'It is read-only and hidden from the sidebar. Restore it to resume work.'
                : 'Archiving makes the project read-only and hides it from the sidebar. Nothing is deleted.'
            }
          >
            <Button
              icon={project.archived ? <ArchiveRestore size={14} aria-hidden="true" /> : <Archive size={14} aria-hidden="true" />}
              loading={archive.isPending}
              onClick={() => { archive.mutate(!project.archived); }}
            >
              {project.archived ? 'Restore project' : 'Archive project'}
            </Button>
          </SettingRow>
        </SettingsCard>
      </SettingsSection>

      <SettingsSection title="Danger zone" tone="danger" description="Irreversible actions. Only organization admins can delete a project.">
        <SettingsCard>
          <SettingRow label="Delete this project" description="All issues, comments, attachments and history are permanently deleted.">
            <Button variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirmDelete(true); }}>
              Delete project…
            </Button>
          </SettingRow>
        </SettingsCard>
      </SettingsSection>

      <Dialog
        open={confirmDelete}
        size="sm"
        title={`Delete ${project.name}?`}
        description="All issues, comments, attachments and history of this project are permanently deleted. Only organization admins can do this."
        onClose={() => { setConfirmDelete(false); }}
        footer={
          <>
            <Button onClick={() => { setConfirmDelete(false); }}>Cancel</Button>
            <Button variant="danger" disabled={confirmKey !== project.key} loading={remove.isPending} onClick={() => { remove.mutate(); }}>
              Delete permanently
            </Button>
          </>
        }
      >
        {remove.isError && <Alert tone="danger">{describeError(remove.error)}</Alert>}
        <Input label={`Type ${project.key} to confirm`} value={confirmKey} onChange={(e) => { setConfirmKey(e.target.value); }} autoComplete="off" />
      </Dialog>
    </>
  );
}

const PERMISSION_LABELS: Record<ProjectPermission, string> = {
  BROWSE: 'Browse issues',
  CREATE_ISSUES: 'Create issues',
  EDIT_ISSUES: 'Edit issues',
  TRANSITION_ISSUES: 'Change status',
  ASSIGN_ISSUES: 'Assign issues',
  DELETE_ISSUES: 'Delete issues',
  COMMENT: 'Comment',
  DELETE_ANY_COMMENT: 'Delete any comment',
  MANAGE_ATTACHMENTS: 'Manage all attachments',
  MANAGE_PROJECT: 'Administer project',
};

export function ProjectAccessSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const toast = useToast();
  const access = useQuery(accessQuery(org, project.key));
  const catalog = useQuery(catalogQuery(org, project.key));
  const members = useQuery(membersQuery(org, '', 0, 200));
  const teams = useQuery(teamsQuery(org));
  const [addUser, setAddUser] = useState('');
  const [addTeam, setAddTeam] = useState('');
  const [role, setRole] = useState('');
  const [editingRole, setEditingRole] = useState<ProjectRole | 'new' | null>(null);
  const roles = catalog.data?.roles ?? [];
  const defaultRole = role || (roles.find((r) => r.systemKey === 'MEMBER')?.id ?? roles[0]?.id ?? '');
  const path = { org, key: project.key };
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.access(org, project.key) });
    await queryClient.invalidateQueries({ queryKey: keys.catalog(org, project.key) });
  };
  const onError = (error: unknown) => { toast.error(describeError(error)); };

  const putMember = useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      call(() => api.PUT('/api/v1/orgs/{org}/projects/{key}/members/{userId}', { params: { path: { ...path, userId } }, body: { roleId } })),
    onSuccess: refresh,
    onError,
  });
  const removeMember = useMutation({
    mutationFn: (userId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/members/{userId}', { params: { path: { ...path, userId } } })),
    onSuccess: refresh,
    onError,
  });
  const putTeam = useMutation({
    mutationFn: ({ teamId, roleId }: { teamId: string; roleId: string }) =>
      call(() => api.PUT('/api/v1/orgs/{org}/projects/{key}/teams/{teamId}', { params: { path: { ...path, teamId } }, body: { roleId } })),
    onSuccess: refresh,
    onError,
  });
  const removeTeam = useMutation({
    mutationFn: (teamId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/teams/{teamId}', { params: { path: { ...path, teamId } } })),
    onSuccess: refresh,
    onError,
  });
  const deleteRole = useMutation({
    mutationFn: (roleId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/roles/{roleId}', { params: { path: { ...path, roleId } } })),
    onSuccess: refresh,
    onError,
  });

  if (access.isPending || catalog.isPending) {
    return <Skeleton lines={6} />;
  }
  if (access.isError) {
    return <Alert tone="danger">{describeError(access.error)}</Alert>;
  }
  const roleSelect = (value: string, onChange: (id: string) => void, label: string) => (
    <Select label={label} hideLabel value={value} onChange={(e) => { onChange(e.target.value); }}>
      {roles.map((r) => (
        <option key={r.id} value={r.id}>
          {r.name}
        </option>
      ))}
    </Select>
  );
  const memberIds = new Set(access.data.members.map((m) => m.userId));
  const teamIds = new Set(access.data.teams.map((t) => t.teamId));

  return (
    <>
      <Alert tone="info">
        Organization owners and admins always administer every project.{' '}
        {project.visibility === 'ORGANIZATION'
          ? 'Other members get the project’s default role unless listed here.'
          : 'This project is private: only the people and teams below can see it.'}
      </Alert>

      <SettingsSection title="People" description="People added directly, each with their own role in this project.">
        <SettingsCard
          flush
          footer={
            <form
              className={styles.addForm}
              onSubmit={(e) => {
                e.preventDefault();
                if (addUser) {
                  putMember.mutate({ userId: addUser, roleId: defaultRole }, { onSuccess: () => { setAddUser(''); } });
                }
              }}
            >
              <Select label="Person" hideLabel className={styles.grow} value={addUser} onChange={(e) => { setAddUser(e.target.value); }}>
                <option value="">Add a person…</option>
                {(members.data?.items ?? []).filter((m) => !memberIds.has(m.userId)).map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.displayName} ({m.role.toLowerCase()})
                  </option>
                ))}
              </Select>
              {roleSelect(defaultRole, setRole, 'Role for new person')}
              <Button type="submit" variant="primary" size="sm" icon={<UserPlus size={14} aria-hidden="true" />} disabled={!addUser} loading={putMember.isPending}>
                Add
              </Button>
            </form>
          }
        >
          {access.data.members.length === 0 ? (
            <SettingsEmpty>No people added directly.</SettingsEmpty>
          ) : (
            <SettingsList label="People with access">
              {access.data.members.map((m) => (
                <SettingsListItem key={m.userId} leading={<Avatar name={m.displayName} />} title={m.displayName} meta={`@${m.handle}`}>
                  {roleSelect(m.roleId, (roleId) => { putMember.mutate({ userId: m.userId, roleId }); }, `Role of ${m.displayName}`)}
                  <Button size="sm" variant="ghost" icon={<X size={14} aria-hidden="true" />} aria-label={`Remove ${m.displayName}`} onClick={() => { removeMember.mutate(m.userId); }} />
                </SettingsListItem>
              ))}
            </SettingsList>
          )}
        </SettingsCard>
      </SettingsSection>

      <SettingsSection title="Teams" description="Give a whole team access with one role. New team members get it automatically.">
        <SettingsCard
          flush
          footer={
            <form
              className={styles.addForm}
              onSubmit={(e) => {
                e.preventDefault();
                if (addTeam) {
                  putTeam.mutate({ teamId: addTeam, roleId: defaultRole }, { onSuccess: () => { setAddTeam(''); } });
                }
              }}
            >
              <Select label="Team" hideLabel className={styles.grow} value={addTeam} onChange={(e) => { setAddTeam(e.target.value); }}>
                <option value="">Add a team…</option>
                {(teams.data ?? []).filter((t) => !teamIds.has(t.id)).map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
              <Button type="submit" variant="primary" size="sm" icon={<UserPlus size={14} aria-hidden="true" />} disabled={!addTeam} loading={putTeam.isPending}>
                Add team
              </Button>
            </form>
          }
        >
          {access.data.teams.length === 0 ? (
            <SettingsEmpty>No teams added.</SettingsEmpty>
          ) : (
            <SettingsList label="Teams with access">
              {access.data.teams.map((t) => (
                <SettingsListItem
                  key={t.teamId}
                  leading={
                    <span className={styles.teamMark}>
                      <UsersRound size={16} aria-hidden="true" />
                    </span>
                  }
                  title={t.teamName}
                  meta={`${String(t.memberCount)} ${t.memberCount === 1 ? 'member' : 'members'}`}
                >
                  {roleSelect(t.roleId, (roleId) => { putTeam.mutate({ teamId: t.teamId, roleId }); }, `Role of team ${t.teamName}`)}
                  <Button size="sm" variant="ghost" icon={<X size={14} aria-hidden="true" />} aria-label={`Remove team ${t.teamName}`} onClick={() => { removeTeam.mutate(t.teamId); }} />
                </SettingsListItem>
              ))}
            </SettingsList>
          )}
        </SettingsCard>
      </SettingsSection>

      <SettingsSection
        title="Roles"
        layout="stacked"
        description="What each role may do in this project. Select a role name to edit it."
        actions={
          <Button variant="primary" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setEditingRole('new'); }}>
            New role
          </Button>
        }
      >
        <SettingsCard flush>
          <div className={styles.scroll}>
            <table className={styles.matrix}>
              <caption className="visually-hidden">Permissions of each role</caption>
              <thead>
                <tr>
                  <th scope="col">Permission</th>
                  {roles.map((r) => (
                    <th key={r.id} scope="col">
                      <button type="button" className={styles.roleButton} onClick={() => { setEditingRole(r); }}>
                        {r.name}
                      </button>
                      {r.systemKey && <Badge>built-in</Badge>}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(Object.keys(PERMISSION_LABELS) as ProjectPermission[]).map((p) => (
                  <tr key={p}>
                    <th scope="row">{PERMISSION_LABELS[p]}</th>
                    {roles.map((r) => (
                      <td key={r.id}>
                        {r.permissions.includes(p) ? (
                          <span className={styles.allowed} role="img" aria-label="Allowed">
                            <Check size={14} aria-hidden="true" />
                          </span>
                        ) : (
                          <span className={styles.denied} role="img" aria-label="Not allowed">
                            <Minus size={14} aria-hidden="true" />
                          </span>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SettingsCard>
      </SettingsSection>

      {editingRole && (
        <RoleDialog
          role={editingRole === 'new' ? undefined : editingRole}
          onClose={() => { setEditingRole(null); }}
          onDelete={editingRole !== 'new' && !editingRole.systemKey ? () => { deleteRole.mutate(editingRole.id, { onSuccess: () => { setEditingRole(null); } }); } : undefined}
          onSaved={refresh}
        />
      )}
    </>
  );
}

function RoleDialog({ role, onClose, onSaved, onDelete }: { role: ProjectRole | undefined; onClose: () => void; onSaved: () => Promise<void>; onDelete: (() => void) | undefined }) {
  const org = useOrgSlug();
  const project = useProject();
  const [name, setName] = useState(role?.name ?? '');
  const [permissions, setPermissions] = useState<ProjectPermission[]>(role?.permissions ?? ['BROWSE', 'COMMENT']);
  const save = useMutation({
    mutationFn: () =>
      call(() =>
        role
          ? api.PATCH('/api/v1/orgs/{org}/projects/{key}/roles/{roleId}', { params: { path: { org, key: project.key, roleId: role.id } }, body: { name: name.trim(), permissions } })
          : api.POST('/api/v1/orgs/{org}/projects/{key}/roles', { params: { path: { org, key: project.key } }, body: { name: name.trim(), permissions } }),
      ),
    onSuccess: async () => {
      await onSaved();
      onClose();
    },
  });
  return (
    <Dialog
      open
      size="sm"
      title={role ? `Role: ${role.name}` : 'New role'}
      onClose={onClose}
      footer={
        <>
          {onDelete && (
            <Button variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} onClick={onDelete}>
              Delete role
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={!name.trim()} onClick={() => { save.mutate(); }}>
            Save
          </Button>
        </>
      }
    >
      {save.isError && <Alert tone="danger">{describeError(save.error)}</Alert>}
      <Input label="Name" maxLength={40} value={name} onChange={(e) => { setName(e.target.value); }} />
      <fieldset className="field-group">
        <legend>Permissions</legend>
        {(Object.keys(PERMISSION_LABELS) as ProjectPermission[]).map((p) => (
          <Checkbox
            key={p}
            label={PERMISSION_LABELS[p]}
            checked={permissions.includes(p)}
            disabled={p === 'BROWSE'}
            onChange={(e) => { setPermissions(e.target.checked ? [...permissions, p] : permissions.filter((x) => x !== p)); }}
          />
        ))}
      </fieldset>
    </Dialog>
  );
}
