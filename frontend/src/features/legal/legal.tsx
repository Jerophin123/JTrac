import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { components } from '../../api/schema';
import { publicConfigQueryOptions } from '../auth/session';
import styles from './LegalPages.module.css';

export type LegalInfo = components['schemas']['LegalInfo'];

/** The operator's legal details from the server configuration (blank strings when not set). */
export function useLegalInfo() {
  const query = useQuery(publicConfigQueryOptions);
  return { ...query, legal: query.data?.legal };
}

/** Whether the operator filled in the details the documents depend on. */
export function isConfigured(legal: LegalInfo): boolean {
  return Boolean(legal.operatorName.trim() && legal.grievanceOfficerEmail.trim());
}

/**
 * A configured value, or a clearly marked placeholder naming the setting to fill in, so an
 * unconfigured installation never presents invented details as real.
 */
export function Value({ value, setting, label }: { value: string; setting: string; label: string }) {
  const text = value.trim();
  if (text) {
    return <>{text}</>;
  }
  return (
    <mark className={styles.placeholder} title={`Set ${setting} on the server`}>
      [{label} not configured]
    </mark>
  );
}

/** An email address as a mailto link, or a placeholder. */
export function Email({ value, setting, label }: { value: string; setting: string; label: string }) {
  const text = value.trim();
  if (!text) {
    return <Value value="" setting={setting} label={label} />;
  }
  return <a href={`mailto:${text}`}>{text}</a>;
}

export function OperatorName({ legal }: { legal: LegalInfo }) {
  return <Value value={legal.operatorName} setting="JTRAC_LEGAL_OPERATOR_NAME" label="Operator name" />;
}

export function GrievanceEmail({ legal }: { legal: LegalInfo }) {
  return <Email value={legal.grievanceOfficerEmail} setting="JTRAC_LEGAL_GRIEVANCE_OFFICER_EMAIL" label="Grievance officer email" />;
}

export function formatLegalDate(value: string): string {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
}

/** A numbered section of a legal document, with an anchor for linking. */
export function Clause({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className={styles.clause} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.clauseTitle}>
        {title}
      </h2>
      {children}
    </section>
  );
}
