import { useQuery, type QueryClient } from '@tanstack/react-query';
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  isRedirect,
  notFound,
  Outlet,
  redirect,
  useNavigate,
} from '@tanstack/react-router';
import { orgQuery, projectQuery } from '../api/queries';
import { AppearancePage, PrivacyPage as AccountPrivacyPage, ProfilePage, TokensPage } from '../features/account/AccountPages';
import { ConsentGate } from '../features/legal/ConsentGate';
import { ServerSettingsPage } from '../features/system/ServerSettingsPage';
import { GrievancePage, PrivacyPage, TermsPage } from '../features/legal/LegalPages';
import { AcceptInvitePage, ForgotPasswordPage, RegisterPage, ResetPasswordPage, VerifyEmailPage } from '../features/auth/AccountFlows';
import { LoginPage } from '../features/auth/LoginPage';
import { SetupPage } from '../features/auth/SetupPage';
import { setupStatusQueryOptions } from '../features/auth/setupApi';
import { safeRedirect, sessionQueryOptions } from '../features/auth/session';
import { BoardView } from '../features/board/BoardView';
import { DashboardPage, DashboardsPage } from '../features/dashboards/DashboardPages';
import { MyWorkPage } from '../features/home/MyWorkPage';
import { parseFilterState, type FilterState } from '../features/issues/filters';
import { IssuePage } from '../features/issues/IssuePage';
import { InboxPage } from '../features/notifications/InboxPage';
import { CreateOrgPage, OrgIndexPage } from '../features/orgs/OrgPages';
import {
  AuditSettings,
  MembersSettings,
  NotificationSettings,
  OrgGeneralSettings,
  OrgSettingsLayout,
  TeamsSettings,
} from '../features/orgs/OrgSettings';
import { WebhookSettings } from '../features/orgs/WebhookSettings';
import { TimelineView } from '../features/planning/TimelineView';
import { ProjectLayout } from '../features/projects/ProjectLayout';
import { ProjectsPage } from '../features/projects/ProjectsPage';
import { CatalogSettings, FieldsSettings, ImportSettings } from '../features/projects/settings/CatalogSettings';
import { ProjectAccessSettings, ProjectGeneralSettings, ProjectSettingsLayout } from '../features/projects/settings/ProjectSettings';
import { WorkflowSettings } from '../features/projects/settings/WorkflowSettings';
import { IssueListView, SearchPage } from '../features/search/SearchPage';
import { ApiError } from '../api/problem';
import { AppShell } from './shell/AppShell';
import { BootScreen, ErrorScreen, NotFoundScreen } from './StatusScreens';

export interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  notFoundComponent: NotFoundScreen,
  errorComponent: ({ error }) => <ErrorScreen error={error} />,
  pendingComponent: BootScreen,
});

interface LoginSearch {
  redirect?: string;
  signedOut?: boolean;
}

const setupRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/setup',
  beforeLoad: async ({ context }) => {
    try {
      const setup = await context.queryClient.query(setupStatusQueryOptions);
      if (!setup.required) {
        // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
        throw redirect({ to: '/login', replace: true });
      }
    } catch (e) {
      if (isRedirect(e)) throw e;
    }
  },
  component: SetupPage,
});

const welcomeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/welcome',
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
    throw redirect({ to: '/setup', replace: true });
  },
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: (search: Record<string, unknown>): LoginSearch => ({
    redirect: safeRedirect(search.redirect),
    signedOut: search.signedOut === true || search.signedOut === 'true' ? true : undefined,
  }),
  beforeLoad: async ({ context, search }) => {
    try {
      const setup = await context.queryClient.query(setupStatusQueryOptions);
      if (setup.required) {
        // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
        throw redirect({ to: '/setup', replace: true });
      }
    } catch (e) {
      if (isRedirect(e)) throw e;
    }
    const session = await context.queryClient.query(sessionQueryOptions);
    if (session) {
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
      throw redirect({ href: search.redirect ?? '/', replace: true });
    }
  },
  component: function LoginRoute() {
    const search = loginRoute.useSearch();
    return <LoginPage redirectTo={search.redirect} signedOut={search.signedOut === true} />;
  },
});

const tokenSearch = (search: Record<string, unknown>): { token?: string } => ({
  token: typeof search.token === 'string' && search.token.length <= 200 ? search.token : undefined,
});

// Legal documents are public: readable before signing up and while signed in.
const termsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/legal/terms', component: TermsPage });
const privacyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/legal/privacy', component: PrivacyPage });
const grievanceRoute = createRoute({ getParentRoute: () => rootRoute, path: '/legal/grievance', component: GrievancePage });
const registerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/register', component: RegisterPage });
const forgotRoute = createRoute({ getParentRoute: () => rootRoute, path: '/forgot-password', component: ForgotPasswordPage });
const resetRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/reset-password',
  validateSearch: tokenSearch,
  component: function ResetRoute() {
    return <ResetPasswordPage token={resetRoute.useSearch().token} />;
  },
});
const verifyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/verify-email',
  validateSearch: tokenSearch,
  component: function VerifyRoute() {
    return <VerifyEmailPage token={verifyRoute.useSearch().token} />;
  },
});
const inviteRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/invite',
  validateSearch: tokenSearch,
  component: function InviteRoute() {
    return <AcceptInvitePage token={inviteRoute.useSearch().token} />;
  },
});

/** Everything below this pathless route requires a session. */
const authenticatedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'authenticated',
  beforeLoad: async ({ context, location }) => {
    // Re-validates the session with the server once the cached value is stale (staleTime).
    const session = await context.queryClient.query(sessionQueryOptions);
    if (!session) {
      try {
        const setup = await context.queryClient.query(setupStatusQueryOptions);
        if (setup.required) {
          // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
          throw redirect({ to: '/setup', replace: true });
        }
      } catch (e) {
        if (isRedirect(e)) throw e;
      }
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
      throw redirect({ to: '/login', search: { redirect: location.href }, replace: true });
    }
  },
  component: AuthenticatedFrame,
});

/** The app, or the consent gate while the current Terms and Privacy Notice are not accepted. */
function AuthenticatedFrame() {
  const { data: session } = useQuery(sessionQueryOptions);
  return session?.consentRequired ? <ConsentGate /> : <AppShell />;
}

const indexRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/', component: OrgIndexPage });
const newOrgRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/new', component: () => <CreateOrgPage /> });
const accountRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/account', component: ProfilePage });
const tokensRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/account/tokens', component: TokensPage });
const appearanceRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/account/appearance', component: AppearancePage });
const serverSettingsRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/server/settings', component: ServerSettingsPage });
const accountPrivacyRoute = createRoute({ getParentRoute: () => authenticatedRoute, path: '/account/privacy', component: AccountPrivacyPage });

/** Loads a resource for a route; a 404 from the API renders the not-found screen. */
async function ensureOrNotFound<T>(load: () => Promise<T>): Promise<T> {
  try {
    return await load();
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router control flow
      throw notFound();
    }
    throw error;
  }
}

const orgRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '$org',
  loader: ({ context, params }) => ensureOrNotFound(() => context.queryClient.query(orgQuery(params.org))),
  component: Outlet,
});

const myWorkRoute = createRoute({ getParentRoute: () => orgRoute, path: '/', component: MyWorkPage });
const projectsRoute = createRoute({ getParentRoute: () => orgRoute, path: 'projects', component: ProjectsPage });
const inboxRoute = createRoute({ getParentRoute: () => orgRoute, path: 'inbox', component: InboxPage });
const dashboardsRoute = createRoute({ getParentRoute: () => orgRoute, path: 'dashboards', component: DashboardsPage });
const dashboardRoute = createRoute({
  getParentRoute: () => orgRoute,
  path: 'dashboards/$id',
  component: function DashboardRoute() {
    return <DashboardPage id={dashboardRoute.useParams().id} />;
  },
});
const issueRoute = createRoute({
  getParentRoute: () => orgRoute,
  path: 'issue/$issueKey',
  component: function IssueRoute() {
    return <IssuePage issueKey={issueRoute.useParams().issueKey.toUpperCase()} />;
  },
});
const searchRoute = createRoute({
  getParentRoute: () => orgRoute,
  path: 'search',
  validateSearch: (search: Record<string, unknown>): FilterState => parseFilterState(search),
  component: function SearchRoute() {
    return <SearchPage state={searchRoute.useSearch()} />;
  },
});

const orgSettingsRoute = createRoute({
  getParentRoute: () => orgRoute,
  path: 'settings',
  component: () => (
    <OrgSettingsLayout>
      <Outlet />
    </OrgSettingsLayout>
  ),
});
const orgSettingsChildren = [
  createRoute({ getParentRoute: () => orgSettingsRoute, path: '/', component: OrgGeneralSettings }),
  createRoute({ getParentRoute: () => orgSettingsRoute, path: 'members', component: MembersSettings }),
  createRoute({ getParentRoute: () => orgSettingsRoute, path: 'teams', component: TeamsSettings }),
  createRoute({ getParentRoute: () => orgSettingsRoute, path: 'notifications', component: NotificationSettings }),
  createRoute({ getParentRoute: () => orgSettingsRoute, path: 'webhooks', component: WebhookSettings }),
  createRoute({ getParentRoute: () => orgSettingsRoute, path: 'audit', component: AuditSettings }),
] as const;

const projectRoute = createRoute({
  getParentRoute: () => orgRoute,
  path: 'p/$key',
  loader: ({ context, params }) => ensureOrNotFound(() => context.queryClient.query(projectQuery(params.org, params.key))),
  component: ProjectLayout,
});

/** Project views keep their filters in the URL (shareable). */
function useFilterNavigation() {
  const navigate = useNavigate();
  return (next: FilterState) => void navigate({ to: '.', search: next as Record<string, unknown>, replace: true });
}

const boardRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: '/',
  validateSearch: (search: Record<string, unknown>): FilterState => parseFilterState(search),
  component: function BoardRoute() {
    return <BoardView state={boardRoute.useSearch()} onChange={useFilterNavigation()} />;
  },
});
const listRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'list',
  validateSearch: (search: Record<string, unknown>): FilterState => parseFilterState(search),
  component: function ListRoute() {
    const params = listRoute.useParams();
    return <IssueListView title={`Issues of ${params.key}`} project={params.key} state={listRoute.useSearch()} onChange={useFilterNavigation()} />;
  },
});
const timelineRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'timeline',
  validateSearch: (search: Record<string, unknown>): FilterState => parseFilterState(search),
  component: function TimelineRoute() {
    return <TimelineView state={timelineRoute.useSearch()} onChange={useFilterNavigation()} />;
  },
});
const projectSettingsRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'settings',
  component: () => (
    <ProjectSettingsLayout>
      <Outlet />
    </ProjectSettingsLayout>
  ),
});
const projectSettingsChildren = [
  createRoute({ getParentRoute: () => projectSettingsRoute, path: '/', component: ProjectGeneralSettings }),
  createRoute({ getParentRoute: () => projectSettingsRoute, path: 'access', component: ProjectAccessSettings }),
  createRoute({ getParentRoute: () => projectSettingsRoute, path: 'workflow', component: WorkflowSettings }),
  createRoute({ getParentRoute: () => projectSettingsRoute, path: 'fields', component: FieldsSettings }),
  createRoute({ getParentRoute: () => projectSettingsRoute, path: 'catalog', component: CatalogSettings }),
  createRoute({ getParentRoute: () => projectSettingsRoute, path: 'import', component: ImportSettings }),
] as const;

const routeTree = rootRoute.addChildren([
  setupRoute,
  welcomeRoute,
  loginRoute,
  registerRoute,
  forgotRoute,
  resetRoute,
  verifyRoute,
  inviteRoute,
  termsRoute,
  privacyRoute,
  grievanceRoute,
  authenticatedRoute.addChildren([
    indexRoute,
    newOrgRoute,
    accountRoute,
    tokensRoute,
    appearanceRoute,
    accountPrivacyRoute,
    serverSettingsRoute,
    orgRoute.addChildren([
      myWorkRoute,
      projectsRoute,
      inboxRoute,
      dashboardsRoute,
      dashboardRoute,
      issueRoute,
      searchRoute,
      orgSettingsRoute.addChildren([...orgSettingsChildren]),
      projectRoute.addChildren([boardRoute, listRoute, timelineRoute, projectSettingsRoute.addChildren([...projectSettingsChildren])]),
    ]),
  ]),
]);

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: 'intent',
    defaultPendingMs: 150,
    scrollRestoration: true,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
