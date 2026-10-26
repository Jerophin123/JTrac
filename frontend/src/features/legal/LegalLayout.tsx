import { Link, useRouterState } from '@tanstack/react-router';
import { ArrowLeft, FileText, Scale, ShieldCheck, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ThemeSwitcher } from '../../app/shell/ThemeSwitcher';
import { Alert } from '../../ui/Alert';
import { Logo } from '../../ui/Logo';
import { Skeleton } from '../../ui/Page';
import { sessionQueryOptions } from '../auth/session';
import { formatLegalDate, isConfigured, useLegalInfo, type LegalInfo } from './legal';
import styles from './LegalPages.module.css';

const DOCUMENTS = [
  { to: '/legal/terms', label: 'Terms of Service', Icon: FileText },
  { to: '/legal/privacy', label: 'Privacy Notice', Icon: ShieldCheck },
  { to: '/legal/grievance', label: 'Grievance Redressal', Icon: Scale },
] as const;

interface LegalLayoutProps {
  title: string;
  summary: string;
  children: (legal: LegalInfo) => ReactNode;
}

/** Shared frame for the legal documents: readable for signed-in and signed-out visitors alike. */
export function LegalLayout({ title, summary, children }: LegalLayoutProps) {
  const { legal, isPending, isError } = useLegalInfo();
  const session = useQuery(sessionQueryOptions);
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  return (
    <div className={styles.page}>
      <header className={styles.topbar}>
        <Link to="/" className={styles.home} aria-label="JTRAC home">
          <Logo height={26} />
        </Link>
        <div className={styles.topActions}>
          <Link to={session.data ? '/' : '/login'} className={styles.back}>
            <ArrowLeft size={15} aria-hidden="true" />
            {session.data ? 'Back to JTRAC' : 'Back to sign in'}
          </Link>
          <ThemeSwitcher />
        </div>
      </header>

      <div className={styles.body}>
        <nav className={styles.nav} aria-label="Legal documents">
          <ul>
            {DOCUMENTS.map(({ to, label, Icon }) => (
              <li key={to}>
                <Link to={to} className={styles.navLink} aria-current={pathname === to ? 'page' : undefined}>
                  <Icon size={16} aria-hidden="true" />
                  {label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        <main className={styles.document} aria-labelledby="legal-title">
          <header className={styles.docHeader}>
            <h1 id="legal-title" className={styles.title}>
              {title}
            </h1>
            <p className={styles.summary}>{summary}</p>
            {legal && (
              <p className={styles.meta}>
                Version {legal.version} · Effective {formatLegalDate(legal.effectiveDate)}
              </p>
            )}
          </header>

          {isPending && <Skeleton lines={8} label="Loading document" />}
          {isError && <Alert tone="danger">This document couldn’t be loaded. Check your connection and try again.</Alert>}
          {legal && !isConfigured(legal) && (
            <div className={styles.notice} role="note">
              <TriangleAlert size={16} aria-hidden="true" />
              <p>
                <strong>Operator details missing.</strong> The administrator of this JTRAC installation hasn’t filled in
                who runs it yet, so some details below are marked as not configured. This text is a template shipped with
                JTRAC; the operator should have it reviewed by a lawyer before relying on it.
              </p>
            </div>
          )}
          {legal && <div className={styles.prose}>{children(legal)}</div>}
        </main>
      </div>
    </div>
  );
}
