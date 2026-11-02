import { useNavigate } from '@tanstack/react-router';
import { SHORTCUT_GROUPS, SHORTCUTS, useShortcutSettings } from '../lib/shortcuts';
import { Alert } from '../ui/Alert';
import { Button } from '../ui/Button';
import { Dialog } from '../ui/Dialog';
import { Kbd } from '../ui/Kbd';
import styles from './ShortcutsDialog.module.css';

interface ShortcutsDialogProps {
  open: boolean;
  onClose: () => void;
}

/** "?" overlay: every shortcut with the keys currently in effect on this device. */
export function ShortcutsDialog({ open, onClose }: ShortcutsDialogProps) {
  const { settings, activeBindingFor } = useShortcutSettings();
  const navigate = useNavigate();
  return (
    <Dialog
      open={open}
      title="Keyboard shortcuts"
      description="Shortcuts with a single key don’t work while you’re typing."
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
          <Button
            variant="primary"
            onClick={() => {
              onClose();
              void navigate({ to: '/account/shortcuts' });
            }}
          >
            Customize shortcuts
          </Button>
        </>
      }
    >
      {!settings.singleKeyEnabled && (
        <Alert tone="info">Single-key shortcuts are turned off; only shortcuts with Ctrl, ⌘ or Alt work.</Alert>
      )}
      <div className={styles.groups}>
        {SHORTCUT_GROUPS.map((group) => (
          <section key={group} className={styles.group} aria-labelledby={`shortcuts-${group}`}>
            <h3 id={`shortcuts-${group}`} className={styles.heading}>
              {group}
            </h3>
            <dl className={styles.list}>
              {SHORTCUTS.filter((s) => s.group === group).map((shortcut) => (
                <div key={shortcut.id} className={styles.row}>
                  <dt>{shortcut.label}</dt>
                  <dd>
                    <Kbd binding={activeBindingFor(shortcut.id)} />
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
    </Dialog>
  );
}
