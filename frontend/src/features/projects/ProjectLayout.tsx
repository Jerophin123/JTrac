import { Link, Outlet } from '@tanstack/react-router';
import { CalendarRange, KanbanSquare, List, Lock, Settings } from 'lucide-react';
import { useOrgSlug, useProject } from '../../app/params';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Page, PageHeader } from '../../ui/Page';
import { TabNav } from '../../ui/Tabs';

/** Frame of every project page: title, archive notice and the view tabs. */
export function ProjectLayout() {
  const org = useOrgSlug();
  const project = useProject();
  const params = { org, key: project.key };
  const manage = project.myPermissions.includes('MANAGE_PROJECT');
  return (
    <Page wide>
      <PageHeader
        eyebrow={
          <>
            <Link to="/$org/projects" params={{ org }}>
              Projects
            </Link>
            <span aria-hidden="true">/</span>
            <span className="issue-key">{project.key}</span>
          </>
        }
        title={
          <span className="row">
            {project.name}
            {project.visibility === 'PRIVATE' && (
              <Badge>
                <Lock size={11} aria-hidden="true" /> Private
              </Badge>
            )}
          </span>
        }
        description={project.description ?? undefined}
      />
      {project.archived && (
        <Alert tone="warning" title="This project is archived">
          It is read-only. {manage ? 'Restore it from the project settings to make changes.' : ''}
        </Alert>
      )}
      <TabNav
        label="Project views"
        tabs={[
          { label: <><KanbanSquare size={15} aria-hidden="true" /> Board</>, link: { to: '/$org/p/$key', params }, exact: true },
          { label: <><List size={15} aria-hidden="true" /> List</>, link: { to: '/$org/p/$key/list', params } },
          { label: <><CalendarRange size={15} aria-hidden="true" /> Timeline</>, link: { to: '/$org/p/$key/timeline', params } },
          ...(manage
            ? [{ label: <><Settings size={15} aria-hidden="true" /> Settings</>, link: { to: '/$org/p/$key/settings' as const, params } }]
            : []),
        ]}
      />
      <Outlet />
    </Page>
  );
}
