import { queryOptions, useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Check, Copy, Cookie, FileText, Scale, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { formatDate } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { Logo } from '../../ui/Logo';
import { Panel, Skeleton } from '../../ui/Page';
import { AccountLayout } from '../account/AccountPages';
import { useIsServerAdmin } from '../auth/session';
import { Email, formatLegalDate, isConfigured, useLegalInfo, Value } from '../legal/legal';
import styles from './AboutPage.module.css';

export const aboutQuery = queryOptions({
  queryKey: ['about'] as const,
  queryFn: () => call(() => api.GET('/api/v1/system/about')),
  staleTime: 5 * 60_000,
});

const DOCUMENTS = [
  { to: '/legal/terms', title: 'Terms of Service', text: 'The rules for using this JTRAC installation.', Icon: FileText },
  { to: '/legal/privacy', title: 'Privacy Notice', text: 'What personal data is processed, why, and your rights.', Icon: ShieldCheck },
  { to: '/legal/cookies', title: 'Cookie Policy', text: 'The two cookies and the preferences stored in your browser.', Icon: Cookie },
  { to: '/legal/grievance', title: 'Grievance Redressal', text: 'How to raise a complaint and what happens next.', Icon: Scale },
] as const;

/** Third-party software the release package lists with its licenses (docs/license-notices.md). */
const COMPONENTS: { name: string; license: string }[] = [
  { name: 'Spring Boot and Spring Framework', license: 'Apache-2.0' },
  { name: 'PostgreSQL (bundled database)', license: 'PostgreSQL License' },
  { name: 'Eclipse Temurin Java runtime', license: 'GPL-2.0 with Classpath Exception' },
  { name: 'React and TanStack', license: 'MIT' },
  { name: 'JLine (setup screen)', license: 'BSD-3-Clause' },
];

/** Account › About JTRAC: version, who runs this installation, the legal documents and the software inside. */
export function AboutPage() {
  const about = useQuery(aboutQuery);
  const { legal } = useLegalInfo();
  const serverAdmin = useIsServerAdmin();

  return (
    <AccountLayout>
      <div className={styles.about}>

      <section className={styles.hero} aria-labelledby="about-app">
        <div className={styles.heroText}>
          {/* The wordmark is the heading; its accessible name is "JTRAC". */}
          <h2 id="about-app" className={styles.product}>
            <Logo height={40} />
          </h2>
          <p className={styles.tagline}>Self-hosted issue and project tracking for teams: boards, workflows, dashboards and more.</p>
          {about.isPending && <Skeleton lines={1} label="Loading version" />}
          {about.isError && <Alert tone="danger">The version couldn’t be loaded.</Alert>}
          {about.data && <VersionLine version={about.data.version} buildTime={about.data.buildTime ?? null} />}
        </div>
      </section>

      <Panel title="This installation">
          {!legal && <Skeleton lines={4} label="Loading operator details" />}
          {legal && (
            <>
              <dl className={styles.facts}>
                <dt>Operated by</dt>
                <dd>
                  <Value value={legal.operatorName} setting="JTRAC_LEGAL_OPERATOR_NAME" label="Operator name" />
                </dd>
                <dt>Contact</dt>
                <dd>
                  <Email value={legal.contactEmail} setting="JTRAC_LEGAL_CONTACT_EMAIL" label="Contact email" />
                </dd>
                <dt>Grievance officer</dt>
                <dd>
                  <Value value={legal.grievanceOfficerName} setting="JTRAC_LEGAL_GRIEVANCE_OFFICER_NAME" label="Grievance officer name" />
                  {legal.grievanceOfficerEmail.trim() && (
                    <>
                      {' · '}
                      <Email value={legal.grievanceOfficerEmail} setting="JTRAC_LEGAL_GRIEVANCE_OFFICER_EMAIL" label="Grievance officer email" />
                    </>
                  )}
                </dd>
                <dt>Jurisdiction</dt>
                <dd>
                  <Value value={legal.jurisdiction} setting="JTRAC_LEGAL_JURISDICTION" label="Jurisdiction" />
                </dd>
              </dl>
              {!isConfigured(legal) && serverAdmin && (
                <p className={styles.hint}>
                  You can fill these in with <code>jtrac configure legal</code> on the server (or the <code>JTRAC_LEGAL_*</code>{' '}
                  settings).
                </p>
              )}
            </>
          )}
        </Panel>


      <Panel
        title="Legal and policies"
        actions={legal && <span className={styles.meta}>Version {legal.version} · effective {formatLegalDate(legal.effectiveDate)}</span>}
        padded={false}
      >
        <ul className={styles.documents}>
          {DOCUMENTS.map(({ to, title, text, Icon }) => (
            <li key={to}>
              <Link to={to} className={styles.document}>
                <span className={styles.docIcon}>
                  <Icon size={18} aria-hidden="true" />
                </span>
                <span className={styles.docText}>
                  <span className={styles.docTitle}>{title}</span>
                  <span className={styles.docSummary}>{text}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </Panel>

      <Panel title="Open-source software">
        <p className={styles.body}>
          JTRAC is built with open-source software. Every release package includes a <code>licenses</code> folder that lists all of
          it with versions and licenses (<code>THIRD-PARTY-JAVA.md</code>, <code>THIRD-PARTY-WEB.md</code>, CycloneDX software bills
          of materials, and the PostgreSQL license); the bundled Java runtime’s notices are in <code>runtime/legal</code>.
        </p>
        <table className={styles.components}>
          <caption className="visually-hidden">Main open-source components and their licenses</caption>
          <thead>
            <tr>
              <th scope="col">Component</th>
              <th scope="col">License</th>
            </tr>
          </thead>
          <tbody>
            {COMPONENTS.map((c) => (
              <tr key={c.name}>
                <td>{c.name}</td>
                <td>{c.license}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
      </div>
    </AccountLayout>
  );
}

function VersionLine({ version, buildTime }: { version: string; buildTime: string | null }) {
  const [copied, setCopied] = useState(false);
  const text = `JTRAC ${version}${buildTime ? ` (built ${formatDate(buildTime)})` : ''}`;
  return (
    <p className={styles.version}>
      <span className={styles.versionBadge}>Version {version}</span>
      {buildTime && <span className={styles.built}>Built {formatDate(buildTime)}</span>}
      <Button
        variant="ghost"
        size="sm"
        icon={copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
        onClick={() => {
          void navigator.clipboard.writeText(text).then(() => {
            setCopied(true);
            setTimeout(() => {
              setCopied(false);
            }, 2000);
          });
        }}
      >
        {copied ? 'Copied' : 'Copy'}
      </Button>
      <span className="visually-hidden" aria-live="polite">
        {copied ? 'Version copied' : ''}
      </span>
    </p>
  );
}
