import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { Archive, ArrowRight, FolderKanban, Globe, Lock, Plus } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, projectsQuery } from '../../api/queries';
import type { ProjectSummary } from '../../api/types';
import { useOrg, useOrgSlug } from '../../app/params';
import { describeError, fieldErrors } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { EmptyState } from '../../ui/EmptyState';
import { Input, Select, TextArea } from '../../ui/Field';
import { Page, PageHeader, Skeleton } from '../../ui/Page';
import { Segmented } from '../../ui/Tabs';
import styles from './ProjectsPage.module.css';
import { EMPTY_STATS, projectStatsQuery, type ProjectStats } from './projectStats';

/** The caller's access in plain words, from the permissions the server reports (display only). */
function accessLevel(permissions: ProjectSummary['myPermissions']): { label: string; tone: 'accent' | 'info' | 'neutral' } {
  if (permissions.includes('MANAGE_PROJECT')) {
    return { label: 'Admin', tone: 'accent' };
  }
  if (permissions.includes('EDIT_ISSUES') || permissions.includes('CREATE_ISSUES')) {
    return { label: 'Can edit', tone: 'info' };
  }
  return { label: 'View only', tone: 'neutral' };
}

/** Ticket counts and a done / in progress / to do bar for one project card. */
function ProjectCounts({ stats, state }: { stats: ProjectStats; state: 'loading' | 'error' | 'ready' }) {
  if (state === 'error') {
    return <span className={styles.countsNote}>Ticket counts are unavailable right now.</span>;
  }
  const show = (value: number) => (state === 'loading' ? '…' : String(value));
  const open = stats.todo + stats.inProgress;
  const tracked = open + stats.done;
  const pct = (value: number) => (tracked === 0 ? 0 : (value / tracked) * 100);
  const donePct = Math.round(pct(stats.done));
  return (
    <span className={styles.counts}>
      <span className={styles.stats}>
        <span className={styles.stat}>
          <span className={styles.statValue}>{show(stats.total)}</span>
          <span className={styles.statLabel}>Total</span>
        </span>
        <span className={styles.stat}>
          <span className={styles.statValue}>{show(open)}</span>
          <span className={styles.statLabel}>Open</span>
        </span>
        <span className={styles.stat}>
          <span className={styles.statValue}>{show(stats.done)}</span>
          <span className={styles.statLabel}>Done</span>
        </span>
        <span className={styles.stat} data-highlight={state === 'ready' && stats.mine > 0 ? true : undefined}>
          <span className={styles.statValue}>{show(stats.mine)}</span>
          <span className={styles.statLabel}>Assigned to me</span>
        </span>
      </span>
      <span className={styles.progressRow}>
        <span className={styles.progress} aria-hidden="true">
          <span className={styles.progressDone} style={{ inlineSize: `${String(pct(stats.done))}%` }} />
          <span className={styles.progressActive} style={{ inlineSize: `${String(pct(stats.inProgress))}%` }} />
        </span>
        <span className={styles.progressLabel}>
          {state === 'loading' ? 'Counting…' : tracked === 0 ? 'No tickets yet' : `${String(donePct)}% done`}
        </span>
      </span>
    </span>
  );
}

export function ProjectsPage() {
  const org = useOrgSlug();
  const detail = useOrg();
  const projects = useQuery(projectsQuery(org));
  const stats = useQuery(projectStatsQuery(org));
  const [show, setShow] = useState<'active' | 'archived'>('active');
  const [creating, setCreating] = useState(false);
  const canCreate = detail.role === 'OWNER' || detail.role === 'ADMIN' || (detail.role === 'MEMBER' && detail.settings.membersCanCreateProjects);
  const list = (projects.data ?? []).filter((p) => (show === 'archived' ? p.archived : !p.archived));

  return (
    <Page>
      <PageHeader
        title="Projects"
        actions={
          canCreate && (
            <Button variant="primary" icon={<Plus size={16} aria-hidden="true" />} onClick={() => { setCreating(true); }}>
              New project
            </Button>
          )
        }
      />
      <Segmented
        label="Show"
        value={show}
        onChange={setShow}
        options={[
          { value: 'active', label: 'Active' },
          { value: 'archived', label: 'Archived' },
        ]}
      />
      {projects.isPending && <Skeleton lines={4} label="Loading projects" />}
      {projects.isError && <Alert tone="danger">{describeError(projects.error)}</Alert>}
      {projects.data && list.length === 0 && (
        <EmptyState icon={<FolderKanban size={20} />} title={show === 'archived' ? 'No archived projects' : 'No projects yet'}>
          {show === 'archived'
            ? 'Archived projects are read-only and appear here.'
            : canCreate
              ? 'Create a project to start tracking work. Pick Kanban for a flexible flow or Structured for review and approval steps.'
              : 'You haven’t been added to a project yet. Ask an administrator for access.'}
        </EmptyState>
      )}
      {list.length > 0 && (
        <ul className={styles.grid} aria-label={show === 'archived' ? 'Archived projects' : 'Projects'}>
          {list.map((project) => {
            const access = accessLevel(project.myPermissions);
            return (
              <li key={project.id}>
                <Link to="/$org/p/$key" params={{ org, key: project.key }} className={styles.card} data-archived={project.archived || undefined}>
                  <span className={styles.top}>
                    <span className={styles.keyTile}>{project.key}</span>
                    <span className={styles.strokes} aria-hidden="true" />
                  </span>
                  <span className={styles.name}>{project.name}</span>
                  <span className={styles.meta}>
                    <span>
                      {project.visibility === 'PRIVATE' ? <Lock size={13} aria-hidden="true" /> : <Globe size={13} aria-hidden="true" />}
                      {project.visibility === 'PRIVATE' ? 'Private: invited people and teams' : 'Visible to the organization'}
                    </span>
                  </span>
                  <ProjectCounts stats={stats.data?.get(project.id) ?? EMPTY_STATS} state={stats.isPending ? 'loading' : stats.isError ? 'error' : 'ready'} />
                  <span className={styles.footer}>
                    <Badge tone={access.tone}>{access.label}</Badge>
                    {project.archived && (
                      <Badge tone="warning">
                        <Archive size={11} aria-hidden="true" /> Archived
                      </Badge>
                    )}
                    <ArrowRight size={16} aria-hidden="true" className={styles.arrow} />
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      <CreateProjectDialog
        org={org}
        open={creating}
        defaultVisibility={detail.settings.defaultProjectVisibility}
        onClose={() => { setCreating(false); }}
      />
    </Page>
  );
}

function CreateProjectDialog({ org, open, onClose, defaultVisibility }: { org: string; open: boolean; onClose: () => void; defaultVisibility: string }) {
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [keyEdited, setKeyEdited] = useState(false);
  const [description, setDescription] = useState('');
  const [visibility, setVisibility] = useState(defaultVisibility);
  const [template, setTemplate] = useState<'KANBAN' | 'STRUCTURED'>('KANBAN');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/projects', {
          params: { path: { org } },
          body: { name: name.trim(), key, description: description.trim() || undefined, visibility: visibility as 'PRIVATE' | 'ORGANIZATION', template },
        }),
      ),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: keys.projects(org) });
      onClose();
      await navigate({ to: '/$org/p/$key', params: { org, key: project.key } });
    },
  });
  const errors = fieldErrors(create.error);
  return (
    <Dialog
      open={open}
      title="New project"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="create-project" loading={create.isPending} disabled={!name.trim() || key.length < 2}>
            Create project
          </Button>
        </>
      }
    >
      {create.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(create.error)}</Alert>}
      <form
        id="create-project"
        className="stack"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          create.mutate();
        }}
      >
        <Input
          label="Name"
          maxLength={80}
          value={name}
          error={errors.name}
          data-autofocus
          onChange={(e) => {
            setName(e.target.value);
            if (!keyEdited) {
              setKey(suggestKey(e.target.value));
            }
          }}
        />
        <Input
          label="Key"
          hint="2–10 capital letters or digits; issues are numbered KEY-1, KEY-2, … The key cannot be changed later."
          maxLength={10}
          value={key}
          error={errors.key}
          onChange={(e) => {
            setKeyEdited(true);
            setKey(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''));
          }}
        />
        <TextArea label="Description" rows={3} value={description} onChange={(e) => { setDescription(e.target.value); }} />
        <div className="form-grid">
          <Select label="Visibility" value={visibility} onChange={(e) => { setVisibility(e.target.value); }}
            hint={visibility === 'PRIVATE' ? 'Only people you add can see it.' : 'Every member of the organization can see it.'}>
            <option value="ORGANIZATION">Organization</option>
            <option value="PRIVATE">Private</option>
          </Select>
          <Select label="Workflow" value={template} onChange={(e) => { setTemplate(e.target.value as 'KANBAN' | 'STRUCTURED'); }}
            hint={template === 'KANBAN' ? 'Backlog → Todo → In Progress → In Review → Done; any move allowed.' : 'Review step; only project admins approve; assignee required while in progress.'}>
            <option value="KANBAN">Kanban</option>
            <option value="STRUCTURED">Structured</option>
          </Select>
        </div>
      </form>
    </Dialog>
  );
}

export function suggestKey(name: string): string {
  const words = name
    .toUpperCase()
    .replace(/[^A-Z0-9 ]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);
  const initials = words.length > 1 ? words.map((w) => w[0]).join('') : (words[0] ?? '').slice(0, 4);
  const key = initials.replace(/^[0-9]+/, '').slice(0, 10);
  return key.length >= 2 ? key : (words[0] ?? '').replace(/^[0-9]+/, '').slice(0, 4);
}
