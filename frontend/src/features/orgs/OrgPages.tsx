import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useNavigate } from '@tanstack/react-router';
import { Building2, MailPlus } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, orgsQuery } from '../../api/queries';
import { describeError, fieldErrors } from '../../lib/errors';
import { readLastOrg } from '../../lib/lastOrg';
import { formatOrgRoleDescription } from '../../lib/roles';
import { Badge } from '../../ui/Badge';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { EmptyState } from '../../ui/EmptyState';
import { Input } from '../../ui/Field';
import { OrgMark } from '../../ui/OrgMark';
import { Page, PageHeader, Panel, Skeleton } from '../../ui/Page';
import { useCanCreateOrganizations } from '../auth/session';
import styles from './OrgSettings.module.css';

/** "/": continue in the last organization, the only one, or let the user choose/create. */
export function OrgIndexPage() {
  const { data: orgs, isPending, isError, error } = useQuery(orgsQuery);
  const canCreateOrgs = useCanCreateOrganizations();
  if (isPending) {
    return <Skeleton lines={4} label="Loading your organizations" />;
  }
  if (isError) {
    return <Alert tone="danger">{describeError(error)}</Alert>;
  }
  const last = readLastOrg();
  const target = orgs.find((o) => o.slug === last) ?? (orgs.length === 1 ? orgs[0] : undefined);
  if (target) {
    return <Navigate to="/$org" params={{ org: target.slug }} replace />;
  }
  if (orgs.length === 0) {
    return <CreateOrgPage first />;
  }
  return (
    <Page>
      <PageHeader title="Choose an organization" />
      <ul className="card-list">
        {orgs.map((org) => (
          <li key={org.id}>
            <Link to="/$org" params={{ org: org.slug }} className="card-link">
              <OrgMark name={org.name} logoUrl={org.logoUrl} color={org.color} size="sm" />
              <span className={styles.orgChoice}>
                <strong>{org.name}</strong>
                <Badge tone={org.role === 'OWNER' || org.role === 'ADMIN' ? 'accent' : 'neutral'}>
                  {formatOrgRoleDescription(org.role)}
                </Badge>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      {canCreateOrgs && <Link to="/new">Create another organization</Link>}
    </Page>
  );
}

export function slugify(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40);
}

/** Shown instead of the create form to people who may not create organizations. */
function JoinByInvitation({ first }: { first: boolean }) {
  return (
    <Page>
      <PageHeader
        title={first ? 'Welcome to JTRAC' : 'New organization'}
        description="Organizations hold your projects, people and settings."
      />
      <EmptyState icon={<MailPlus size={20} />} title={first ? 'You’re not in an organization yet' : 'Only a server administrator can create organizations'}>
        Ask an administrator of your team’s organization to invite you. The invitation arrives by email; once you accept it, the
        organization shows up here.
      </EmptyState>
    </Page>
  );
}

export function CreateOrgPage({ first = false }: { first?: boolean }) {
  if (!useCanCreateOrganizations()) {
    return <JoinByInvitation first={first} />;
  }
  return <CreateOrgForm first={first} />;
}

function CreateOrgForm({ first }: { first: boolean }) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs', { body: { name: name.trim(), slug: slug || undefined } })),
    onSuccess: async (org) => {
      await queryClient.invalidateQueries({ queryKey: keys.orgs });
      await navigate({ to: '/$org', params: { org: org.slug } });
    },
  });
  const errors = fieldErrors(create.error);
  return (
    <Page>
      <PageHeader
        title={first ? 'Welcome to JTRAC' : 'New organization'}
        description={
          first
            ? 'Organizations hold your projects, people and settings. Create one for your team, then invite colleagues.'
            : 'Each organization has its own members, projects and settings.'
        }
      />
      {first && (
        <EmptyState icon={<Building2 size={20} />} title="You’re not in an organization yet">
          If a colleague already uses JTRAC, ask them for an invitation instead.
        </EmptyState>
      )}
      <Panel title="Organization details">
        {create.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(create.error)}</Alert>}
        <form
          className="stack"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            create.mutate();
          }}
        >
          <Input
            label="Name"
            required
            maxLength={80}
            value={name}
            error={errors.name}
            onChange={(e) => {
              setName(e.target.value);
              if (!slugEdited) {
                setSlug(slugify(e.target.value));
              }
            }}
          />
          <Input
            label="URL"
            hint={`Your organization will live at ${window.location.host}/${slug || 'your-team'}`}
            maxLength={40}
            pattern="[a-z0-9-]+"
            value={slug}
            error={errors.slug}
            onChange={(e) => {
              setSlugEdited(true);
              setSlug(e.target.value.toLowerCase());
            }}
          />
          <div>
            <Button type="submit" variant="primary" loading={create.isPending} disabled={!name.trim()}>
              Create organization
            </Button>
          </div>
        </form>
      </Panel>
    </Page>
  );
}
