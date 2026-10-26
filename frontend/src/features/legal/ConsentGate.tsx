import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ExternalLink, FileText, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { describeError } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { AuthLayout } from '../auth/AuthLayout';
import { logout, sessionQueryKey } from '../auth/session';
import { ConsentCheckbox } from './ConsentCheckbox';
import { formatLegalDate, useLegalInfo } from './legal';
import styles from './Consent.module.css';

/**
 * Shown instead of the app while the signed-in user hasn't accepted the current Terms of
 * Service and Privacy Notice: on first sign-in for older accounts, after the terms change, or
 * after withdrawing consent.
 */
export function ConsentGate() {
  const queryClient = useQueryClient();
  const { legal } = useLegalInfo();
  const [checked, setChecked] = useState(false);
  const [touched, setTouched] = useState(false);
  const accept = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/me/consent')),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
  const navigate = useNavigate();
  const signOut = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      queryClient.setQueryData(sessionQueryKey, null);
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== sessionQueryKey[0] });
      await navigate({ to: '/login', search: { signedOut: true } });
    },
  });

  return (
    <AuthLayout
      title="Review our terms"
      subtitle={
        legal
          ? `Before you continue, please read and accept the Terms of Service and Privacy Notice (version ${legal.version}, effective ${formatLegalDate(legal.effectiveDate)}).`
          : 'Before you continue, please read and accept the Terms of Service and Privacy Notice.'
      }
    >
      <ul className={styles.gateLinks}>
        <li>
          <a href="/legal/terms" target="_blank" rel="noopener" className={styles.gateLink}>
            <FileText size={16} aria-hidden="true" />
            <span>Terms of Service</span>
            <ExternalLink size={14} aria-label="opens in a new tab" />
          </a>
        </li>
        <li>
          <a href="/legal/privacy" target="_blank" rel="noopener" className={styles.gateLink}>
            <ShieldCheck size={16} aria-hidden="true" />
            <span>Privacy Notice</span>
            <ExternalLink size={14} aria-label="opens in a new tab" />
          </a>
        </li>
      </ul>
      {accept.isError && <Alert tone="danger">{describeError(accept.error)}</Alert>}
      <form
        className={styles.gateActions}
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          setTouched(true);
          if (checked) {
            accept.mutate();
          }
        }}
      >
        <ConsentCheckbox
          checked={checked}
          error={touched && !checked ? 'Tick the box to accept, or sign out.' : undefined}
          onChange={(event) => {
            setChecked(event.target.checked);
          }}
        />
        <Button type="submit" variant="primary" block loading={accept.isPending}>
          Accept and continue
        </Button>
        <Button
          type="button"
          variant="ghost"
          block
          loading={signOut.isPending}
          onClick={() => {
            signOut.mutate();
          }}
        >
          Decline and sign out
        </Button>
      </form>
    </AuthLayout>
  );
}
