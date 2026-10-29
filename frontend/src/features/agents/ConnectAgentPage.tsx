import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Bot, KeyRound, ShieldCheck, Undo2 } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import type { AgentConsent } from '../../api/types';
import { describeError, isNotFound } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Skeleton } from '../../ui/Page';
import { AuthLayout, type ShowcaseContent } from '../auth/AuthLayout';
import styles from './AgentConnections.module.css';
import { leaveTo } from './navigation';

const SHOWCASE: ShowcaseContent = {
  tagline: (
    <>
      Your agent works <em>as you</em>, never more.
    </>
  ),
  lede: 'An AI agent is asking to use one of your JTRAC agent connections.',
  points: [
    { Icon: ShieldCheck, text: 'It only sees what you can see in this organization' },
    { Icon: KeyRound, text: 'It never learns your password' },
    { Icon: Undo2, text: 'Revoke it any time under Account › AI agents' },
    { Icon: Bot, text: 'Everything it does is recorded in the connection’s activity' },
  ],
};

const ERRORS: Record<string, string> = {
  unknown_client: 'The agent is not registered with this JTRAC server. Start the sign-in again from your agent.',
  invalid_redirect_uri: 'The agent asked to return to an address it did not register. For your safety the sign-in was stopped.',
  invalid_request: 'The sign-in request was malformed. Start it again from your agent.',
};

export function ConnectAgentPage({ request, error }: { request?: string | undefined; error?: string | undefined }) {
  if (error || !request) {
    return (
      <AuthLayout title="Agent sign-in failed" showcase={SHOWCASE}>
        <Alert tone="danger">{(error && ERRORS[error]) ?? 'This sign-in link is incomplete. Start the sign-in again from your agent.'}</Alert>
        <Link to="/account/ai-agent-connections">Go to your AI agent connections</Link>
      </AuthLayout>
    );
  }
  return <Consent handle={request} />;
}

function Consent({ handle }: { handle: string }) {
  const consent = useQuery({
    queryKey: ['me', 'agent-authorizations', handle],
    queryFn: () => call(() => api.GET('/api/v1/me/agent-authorizations/{handle}', { params: { path: { handle } } })),
    retry: false,
    staleTime: Infinity,
  });
  const [done, setDone] = useState<'approved' | 'denied' | null>(null);
  const decide = useMutation({
    mutationFn: (approve: boolean) =>
      call(() =>
        approve
          ? api.POST('/api/v1/me/agent-authorizations/{handle}/approve', { params: { path: { handle } } })
          : api.POST('/api/v1/me/agent-authorizations/{handle}/deny', { params: { path: { handle } } }),
      ),
    onSuccess: (decision, approve) => {
      setDone(approve ? 'approved' : 'denied');
      // Hands the result (a one-time code, or access_denied) back to the agent.
      leaveTo(decision.redirectUrl);
    },
  });

  if (done) {
    return (
      <AuthLayout title={done === 'approved' ? 'Agent approved' : 'Request denied'} showcase={SHOWCASE}>
        <Alert tone={done === 'approved' ? 'success' : 'info'}>
          {done === 'approved' ? 'Return to your agent: it is finishing the sign-in. You can close this tab.' : 'The agent was told you said no. You can close this tab.'}
        </Alert>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title="Allow an AI agent?" subtitle="Check the details before you approve." showcase={SHOWCASE}>
      {consent.isPending && <Skeleton lines={5} />}
      {consent.isError &&
        (isNotFound(consent.error) ? (
          <Alert tone="danger">
            This request has expired, was already answered, or is for a connection that is not yours. Start the sign-in again from your agent.
          </Alert>
        ) : (
          <Alert tone="danger">{describeError(consent.error)}</Alert>
        ))}
      {consent.data && <ConsentDetails consent={consent.data} pending={decide.isPending} error={decide.error} onDecide={(approve) => { decide.mutate(approve); }} />}
    </AuthLayout>
  );
}

function ConsentDetails({ consent, pending, error, onDecide }: { consent: AgentConsent; pending: boolean; error: Error | null; onDecide: (approve: boolean) => void }) {
  const writes = consent.granted.filter((s) => s.write);
  return (
    <div className={styles.consent}>
      {error && <Alert tone="danger">{describeError(error)}</Alert>}
      <dl className={styles.consentFacts}>
        <div>
          <dt>Agent (name as reported by the agent)</dt>
          <dd>
            <strong>{consent.clientName}</strong>
          </dd>
        </div>
        <div>
          <dt>Connection</dt>
          <dd>
            {consent.connectionName} in {consent.organizationName}
          </dd>
        </div>
        <div>
          <dt>Returns to</dt>
          <dd>
            <code>{consent.redirectHost}</code> <RedirectBadge kind={consent.redirectKind} />
          </dd>
        </div>
      </dl>
      {consent.redirectKind === 'LOOPBACK' && (
        <Alert tone="info">
          The result goes to a program on this computer. Approve only if you started this sign-in from your agent just now: other programs on your computer could
          pretend to be it.
        </Alert>
      )}
      {consent.redirectKind === 'UNRECOGNISED' && (
        <Alert tone="warning" title="Unrecognised website">
          {consent.redirectHost} is not a known agent service. Approve only if you trust it and expected it to connect.
        </Alert>
      )}
      <div>
        <h2 className={styles.subheading}>It will be able to</h2>
        {consent.granted.length === 0 ? (
          <p className={styles.hint}>Nothing: none of the requested permissions are enabled for this connection.</p>
        ) : (
          <ul className={styles.scopeList}>
            {consent.granted.map((s) => (
              <li key={s.name}>
                <span className={styles.scopeName}>
                  {s.name} {s.write && <Badge tone="warning">changes data</Badge>}
                </span>
                <span>{s.description}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      {consent.notEnabled.length > 0 && (
        <p className={styles.hint}>
          Also requested but not enabled for this connection: {consent.notEnabled.map((s) => s.name).join(', ')}. Change this under Account › AI agents.
        </p>
      )}
      {writes.length > 0 && <Alert tone="warning">This agent will be able to change issues as you.</Alert>}
      <p className={styles.hint}>It acts with your permissions in {consent.organizationName} only, and only while your account and this connection are active.</p>
      <div className={styles.decision}>
        <Button onClick={() => { onDecide(false); }} disabled={pending}>
          Deny
        </Button>
        <Button variant="primary" loading={pending} disabled={consent.granted.length === 0} onClick={() => { onDecide(true); }}>
          Approve
        </Button>
      </div>
    </div>
  );
}

function RedirectBadge({ kind }: { kind: AgentConsent['redirectKind'] }) {
  if (kind === 'LOOPBACK') return <Badge tone="info">this computer</Badge>;
  if (kind === 'RECOGNISED') return <Badge tone="success">recognised agent service</Badge>;
  return <Badge tone="warning">unrecognised</Badge>;
}
