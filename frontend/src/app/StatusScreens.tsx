import { Link, useRouter } from '@tanstack/react-router';
import { ApiError, isNetworkError } from '../api/problem';
import { Button } from '../ui/Button';
import { LogoMark } from '../ui/Logo';
import { Spinner } from '../ui/Spinner';
import styles from './StatusScreens.module.css';

export function BootScreen() {
  return (
    <div className={styles.screen}>
      <LogoMark size={40} />
      <Spinner label="Loading JTRAC" />
    </div>
  );
}

export function ErrorScreen({ error }: { error: unknown }) {
  const router = useRouter();
  const offline = isNetworkError(error);
  const requestId = error instanceof ApiError ? error.problem?.requestId : undefined;
  return (
    <div className={styles.screen} role="alert">
      <LogoMark size={40} />
      <h1 className={styles.title}>{offline ? 'JTRAC can’t be reached' : 'Something went wrong'}</h1>
      <p className={styles.text}>
        {offline
          ? 'The server is not responding. Check that the JTRAC service is running, then try again.'
          : 'An unexpected error occurred while loading this page.'}
      </p>
      {requestId && (
        <p className={styles.reference}>
          Reference: <span className="mono">{requestId}</span>
        </p>
      )}
      <Button variant="primary" onClick={() => void router.invalidate()}>
        Try again
      </Button>
    </div>
  );
}

export function NotFoundScreen() {
  return (
    <div className={styles.screen}>
      <LogoMark size={40} />
      <h1 className={styles.title}>Page not found</h1>
      <p className={styles.text}>The page you’re looking for doesn’t exist or has moved.</p>
      <Link to="/" className={styles.link}>
        Go to Home
      </Link>
    </div>
  );
}
